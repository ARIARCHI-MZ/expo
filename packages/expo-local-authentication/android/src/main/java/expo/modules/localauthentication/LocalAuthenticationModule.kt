// Copyright 2015-present 650 Industries. All rights reserved.
package expo.modules.localauthentication

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.PromptInfo
import androidx.fragment.app.FragmentActivity
import expo.modules.core.ExportedModule
import expo.modules.core.ModuleRegistry
import expo.modules.core.ModuleRegistryDelegate
import expo.modules.core.Promise
import expo.modules.core.interfaces.ActivityEventListener
import expo.modules.core.interfaces.ActivityProvider
import expo.modules.core.interfaces.ExpoMethod
import expo.modules.core.interfaces.services.UIManager
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class LocalAuthenticationModule(context: Context) : ExportedModule(context), ActivityEventListener {
  private val AUTHENTICATION_TYPE_FINGERPRINT = 1
  private val AUTHENTICATION_TYPE_FACIAL_RECOGNITION = 2
  private val AUTHENTICATION_TYPE_IRIS = 3
  private val SECURITY_LEVEL_NONE = 0
  private val SECURITY_LEVEL_SECRET = 1
  private val SECURITY_LEVEL_BIOMETRIC = 2
  private val DEVICE_CREDENTIAL_FALLBACK_CODE = 6
  private val biometricManager = BiometricManager.from(context)
  private val packageManager = context.packageManager
  private var biometricPrompt: BiometricPrompt? = null
  private var promise: Promise? = null
  private var authOptions: Map<String?, Any?>? = null
  private var isRetryingWithDeviceCredentials = false
  private var isAuthenticating = false
  private val moduleRegistryDelegate: ModuleRegistryDelegate = ModuleRegistryDelegate()
  private val uIManager: UIManager by moduleRegistry()

  private inline fun <reified T> moduleRegistry() = moduleRegistryDelegate.getFromModuleRegistry<T>()

  private fun convertErrorCode(code: Int): String {
    return when (code) {
      BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_USER_CANCELED -> "user_cancel"
      BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_HW_UNAVAILABLE, BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> "not_available"
      BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "lockout"
      BiometricPrompt.ERROR_NO_SPACE -> "no_space"
      BiometricPrompt.ERROR_TIMEOUT -> "timeout"
      BiometricPrompt.ERROR_UNABLE_TO_PROCESS -> "unable_to_process"
      else -> "unknown"
    }
  }

  private fun isBiometricUnavailable(code: Int): Boolean {
    return when (code) {
      BiometricPrompt.ERROR_HW_NOT_PRESENT,
      BiometricPrompt.ERROR_HW_UNAVAILABLE,
      BiometricPrompt.ERROR_NO_BIOMETRICS,
      BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
      BiometricPrompt.ERROR_NO_SPACE -> true
      else -> false
    }
  }

  private val authenticationCallback: BiometricPrompt.AuthenticationCallback = object : BiometricPrompt.AuthenticationCallback() {
    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
      isAuthenticating = false
      isRetryingWithDeviceCredentials = false
      biometricPrompt = null
      promise?.resolve(
        Bundle().apply {
          putBoolean("success", true)
        }
      )
      promise = null
      authOptions = null
    }

    override fun onAuthenticationError(errMsgId: Int, errString: CharSequence) {
      // Make sure to fallback to the Device Credentials if the Biometrics hardware is unavailable.
      if (isBiometricUnavailable(errMsgId) && isDeviceSecure && !isRetryingWithDeviceCredentials) {
        val options = authOptions

        if (options != null) {
          val disableDeviceFallback = options["disableDeviceFallback"] as Boolean?

          // Don't run the device credentials fallback if it's disabled.
          if (disableDeviceFallback != true) {
            promise?.let {
              isRetryingWithDeviceCredentials = true
              promptDeviceCredentialsFallback(options, it)
              return
            }
          }
        }
      }

      isAuthenticating = false
      isRetryingWithDeviceCredentials = false
      biometricPrompt = null
      promise?.resolve(
        Bundle().apply {
          putBoolean("success", false)
          putString("error", convertErrorCode(errMsgId))
          putString("warning", errString.toString())
        }
      )
      promise = null
      authOptions = null
    }
  }

  override fun getName(): String {
    return "ExpoLocalAuthentication"
  }

  override fun onCreate(moduleRegistry: ModuleRegistry) {
    moduleRegistryDelegate.onCreate(moduleRegistry)
    uIManager.registerActivityEventListener(this)
  }

  @ExpoMethod
  fun supportedAuthenticationTypesAsync(promise: Promise) {
    val result = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    val results: MutableList<Int> = ArrayList()
    if (result == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
      promise.resolve(results)
      return
    }

    // note(cedric): replace hardcoded system feature strings with constants from
    // PackageManager when dropping support for Android SDK 28
    if (packageManager.hasSystemFeature("android.hardware.fingerprint")) {
      results.add(AUTHENTICATION_TYPE_FINGERPRINT)
    }

    if (Build.VERSION.SDK_INT >= 29) {
      if (packageManager.hasSystemFeature("android.hardware.biometrics.face")) {
        results.add(AUTHENTICATION_TYPE_FACIAL_RECOGNITION)
      }
      if (packageManager.hasSystemFeature("android.hardware.biometrics.iris")) {
        results.add(AUTHENTICATION_TYPE_IRIS)
      }
    }

    // check for face recognition support on some samsung devices
    if (packageManager.hasSystemFeature("com.samsung.android.bio.face") && !results.contains(AUTHENTICATION_TYPE_FACIAL_RECOGNITION)) {
      results.add(AUTHENTICATION_TYPE_FACIAL_RECOGNITION)
    }

    promise.resolve(results)
  }

  @ExpoMethod
  fun hasHardwareAsync(promise: Promise) {
    val result = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    promise.resolve(result != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)
  }

  @ExpoMethod
  fun isEnrolledAsync(promise: Promise) {
    val result = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    promise.resolve(result == BiometricManager.BIOMETRIC_SUCCESS)
  }

  @ExpoMethod
  fun getEnrolledLevelAsync(promise: Promise) {
    var level = SECURITY_LEVEL_NONE
    if (isDeviceSecure) {
      level = SECURITY_LEVEL_SECRET
    }
    val result = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    if (result == BiometricManager.BIOMETRIC_SUCCESS) {
      level = SECURITY_LEVEL_BIOMETRIC
    }
    promise.resolve(level)
  }

  @ExpoMethod
  fun authenticateAsync(options: Map<String?, Any?>, promise: Promise) {
    if (currentActivity == null) {
      promise.reject("E_NOT_FOREGROUND", "Cannot display biometric prompt when the app is not in the foreground")
      return
    }
    if (!keyguardManager.isDeviceSecure) {
      promise.resolve(
        Bundle().apply {
          putBoolean("success", false)
          putString("error", "not_enrolled")
          putString("warning", "KeyguardManager#isDeviceSecure() returned false")
        }
      )
      return
    }
    val fragmentActivity = currentActivity as FragmentActivity?
    if (fragmentActivity == null) {
      promise.resolve(
        Bundle().apply {
          putBoolean("success", false)
          putString("error", "not_available")
          putString("warning", "getCurrentActivity() returned null")
        }
      )
      return
    }

    this.authOptions = options

    // BiometricPrompt callbacks are invoked on the main thread so also run this there to avoid
    // having to do locking.
    uIManager.runOnUiQueueThread(
      Runnable {
        if (isAuthenticating) {
          this.promise?.resolve(
            Bundle().apply {
              putBoolean("success", false)
              putString("error", "app_cancel")
            }
          )
          this.promise = promise
          return@Runnable
        }
        val promptMessage = if (options.containsKey("promptMessage")) {
          options["promptMessage"] as String?
        } else {
          ""
        }
        val cancelLabel = if (options.containsKey("cancelLabel")) {
          options["cancelLabel"] as String?
        } else {
          ""
        }
        val disableDeviceFallback = if (options.containsKey("disableDeviceFallback")) {
          options["disableDeviceFallback"] as Boolean?
        } else {
          false
        }
        val requireConfirmation = options["requireConfirmation"] as? Boolean ?: true
        isAuthenticating = true
        this.promise = promise
        val executor: Executor = Executors.newSingleThreadExecutor()
        biometricPrompt = BiometricPrompt(fragmentActivity, executor, authenticationCallback)
        val promptInfoBuilder = PromptInfo.Builder()
        promptMessage?.let {
          promptInfoBuilder.setTitle(it)
        }
        if (disableDeviceFallback == true) {
          cancelLabel?.let {
            promptInfoBuilder.setNegativeButtonText(it)
          }
        } else {
          promptInfoBuilder.setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_WEAK
              or BiometricManager.Authenticators.DEVICE_CREDENTIAL
          )
        }
        promptInfoBuilder.setConfirmationRequired(requireConfirmation)
        val promptInfo = promptInfoBuilder.build()
        try {
          biometricPrompt!!.authenticate(promptInfo)
        } catch (ex: NullPointerException) {
          promise.reject("E_INTERNAL_ERRROR", "Canceled authentication due to an internal error")
        }
      }
    )
  }

  @ExpoMethod
  fun cancelAuthenticate(promise: Promise) {
    uIManager.runOnUiQueueThread {
      biometricPrompt?.cancelAuthentication()
      isAuthenticating = false
      promise.resolve(null)
    }
  }

  fun promptDeviceCredentialsFallback(options: Map<String?, Any?>, promise: Promise) {
    val fragmentActivity = currentActivity as FragmentActivity?
    if (fragmentActivity == null) {
      promise.resolve(
        Bundle().apply {
          putBoolean("success", false)
          putString("error", "not_available")
          putString("warning", "getCurrentActivity() returned null")
        }
      )
      return
    }

    val promptMessage = options["promptMessage"] as? String ?: ""
    val requireConfirmation = options["requireConfirmation"] as? Boolean ?: true

    // BiometricPrompt callbacks are invoked on the main thread so also run this there to avoid
    // having to do locking.
    uIManager.runOnUiQueueThread(
      Runnable {
        // On Android devices older than 11, we need to use Keyguard to unlock by Device Credentials.
        if (Build.VERSION.SDK_INT < 30) {
          val credentialConfirmationIntent = keyguardManager.createConfirmDeviceCredentialIntent(promptMessage, "")
          fragmentActivity.startActivityForResult(credentialConfirmationIntent, DEVICE_CREDENTIAL_FALLBACK_CODE)
          return@Runnable
        }

        val executor: Executor = Executors.newSingleThreadExecutor()
        val localBiometricPrompt = BiometricPrompt(fragmentActivity, executor, authenticationCallback)
        if (localBiometricPrompt == null) {
          promise.reject("E_INTERNAL_ERRROR", "Canceled authentication due to an internal error")
          return@Runnable
        }
        biometricPrompt = localBiometricPrompt

        val promptInfoBuilder = PromptInfo.Builder()
        promptMessage?.let {
          promptInfoBuilder.setTitle(it)
        }
        promptInfoBuilder.setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        promptInfoBuilder.setConfirmationRequired(requireConfirmation)
        val promptInfo = promptInfoBuilder.build()
        try {
          localBiometricPrompt.authenticate(promptInfo)
        } catch (ex: NullPointerException) {
          promise.reject("E_INTERNAL_ERRROR", "Canceled authentication due to an internal error")
        }
      }
    )
  }

  override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
    // When Biometric is unavailable and using Keyguard fallback, the result will be handled here.
    if (requestCode == DEVICE_CREDENTIAL_FALLBACK_CODE) {
      if (resultCode == Activity.RESULT_OK) {
        promise?.resolve(
          Bundle().apply {
            putBoolean("success", true)
          }
        )
      } else {
        promise?.resolve(
          Bundle().apply {
            putBoolean("success", false)
            putString("error", "user_cancel")
            putString("warning", "Device Credentials canceled")
          }
        )
      }

      isAuthenticating = false
      isRetryingWithDeviceCredentials = false
      biometricPrompt = null
      promise = null
      authOptions = null
    } else if (activity is FragmentActivity) {
      // If the user uses PIN as an authentication method, the result will be passed to the `onActivityResult`.
      // Unfortunately, react-native doesn't pass this value to the underlying fragment - we won't resolve the promise.
      // So we need to do it manually.
      val fragment = activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment")
      fragment?.onActivityResult(requestCode and 0xffff, resultCode, data)
    }
  }

  override fun onNewIntent(intent: Intent) = Unit

  // NOTE: `KeyguardManager#isKeyguardSecure()` considers SIM locked state,
  // but it will be ignored on falling-back to device credential on biometric authentication.
  // That means, setting level to `SECURITY_LEVEL_SECRET` might be misleading for some users.
  // But there is no equivalent APIs prior to M.
  // `andriodx.biometric.BiometricManager#canAuthenticate(int)` looks like an alternative,
  // but specifying `BiometricManager.Authenticators.DEVICE_CREDENTIAL` alone is not
  // supported prior to API 30.
  // https://developer.android.com/reference/androidx/biometric/BiometricManager#canAuthenticate(int)
  private val isDeviceSecure: Boolean
    get() = keyguardManager.isDeviceSecure

  private val keyguardManager: KeyguardManager
    get() = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
  private val currentActivity: Activity?
    get() {
      val activityProvider: ActivityProvider by moduleRegistry()
      return activityProvider.currentActivity
    }
}
