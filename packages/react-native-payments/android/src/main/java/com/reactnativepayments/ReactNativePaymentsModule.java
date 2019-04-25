package com.reactnativepayments;

import android.app.Activity;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.util.Log;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.google.android.gms.wallet.AutoResolveHelper;
import com.google.android.gms.wallet.CardRequirements;
import com.google.android.gms.wallet.PaymentDataRequest;
import com.google.android.gms.wallet.PaymentMethodTokenizationParameters;
import com.google.android.gms.wallet.PaymentsClient;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.wallet.PaymentData;
import com.google.android.gms.wallet.TransactionInfo;
import com.google.android.gms.wallet.Wallet;
import com.google.android.gms.wallet.WalletConstants;
import com.google.android.gms.wallet.IsReadyToPayRequest;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

public class ReactNativePaymentsModule extends ReactContextBaseJavaModule {
  public static final String REACT_CLASS = "ReactNativePayments";

  private static final int LOAD_PAYMENT_DATA_REQUEST_CODE = 42;

  private static final String E_NO_PAYMENT_REQUEST_JSON = "E_NO_PAYMENT_REQUEST_JSON";

  private static final String E_NO_PAYMENT_REQUEST = "E_NO_PAYMENT_REQUEST";

  private static final String E_NO_ACTIVITY = "E_NO_ACTIVITY";

  private static final String E_PAYMENT_DATA = "E_PAYMENT_DATA";

  private static final String PAYMENT_CANCELLED = "PAYMENT_CANCELLED";

  private static final String E_AUTO_RESOLVE_FAILED = "E_AUTO_RESOLVE_FAILED";

  private static final String NOT_READY_TO_PAY = "NOT_READY_TO_PAY";

  private static final String E_FAILED_TO_DETECT_IF_READY = "E_FAILED_TO_DETECT_IF_READY";

  private static final String ENVIRONMENT_PRODUCTION_KEY = "ENVIRONMENT_PRODUCTION";

  private static final String ENVIRONMENT_TEST_KEY = "ENVIRONMENT_TEST";


  private static ReactApplicationContext reactContext = null;

  private static JSONObject getBaseRequest() throws JSONException {
        return new JSONObject()
                .put("apiVersion", 2)
                .put("apiVersionMinor", 0);
  }

  /**
   * Identify your gateway and your app's gateway merchant identifier
   *
   * <p>The Google Pay API response will return an encrypted payment method capable of being charged
   * by a supported gateway after payer authorization
   *
   * @return payment data tokenization for the CARD payment method
   * @throws JSONException
   * @see <a
   *     href="https://developers.google.com/pay/api/android/reference/object#PaymentMethodTokenizationSpecification">PaymentMethodTokenizationSpecification</a>
   */
  private static JSONObject getTokenizationSpecification(ReadableMap tokenizationParameters) throws JSONException {
      ReadableMap parameters = tokenizationParameters.getMap("parameters");

      JSONObject tokenizationSpecification = new JSONObject();
      tokenizationSpecification.put("type", "PAYMENT_GATEWAY");
      tokenizationSpecification.put(
              "parameters",
              new JSONObject()
                      .put("gateway", parameters.getString("gateway"))
                      .put("stripe:version", parameters.getString("stripe:version"))
                      .put("stripe:publishableKey", parameters.getString("stripe:publishableKey")));

      return tokenizationSpecification;
  }

  /**
   * Card networks supported by your app and your gateway
   * @return allowed card networks
   * @see <a
   *     href="https://developers.google.com/pay/api/android/reference/object#CardParameters">CardParameters</a>
   */
  private static JSONArray getAllowedCardNetworks(ReadableArray supportedNetworks) {

    JSONArray jsonArray = new JSONArray();

    for (Object value: supportedNetworks.toArrayList()) {
      jsonArray.put(value.toString());
    };

    return jsonArray;
  }

  /**
   * Card authentication methods supported by your app and your gateway
   *
   * @return allowed card authentication methods
   * @see <a
   *     href="https://developers.google.com/pay/api/android/reference/object#CardParameters">CardParameters</a>
   */
  private static JSONArray getAllowedCardAuthMethods() {
    return new JSONArray()
            .put("PAN_ONLY");
  }

  /**
   * Describe your app's support for the CARD payment method
   *
   * <p>The provided properties are applicable to both an IsReadyToPayRequest and a
   * PaymentDataRequest
   *
   * @return a CARD PaymentMethod object describing accepted cards
   * @throws JSONException
   * @see <a
   *     href="https://developers.google.com/pay/api/android/reference/object#PaymentMethod">PaymentMethod</a>
   */
  private static JSONObject getBaseCardPaymentMethod(ReadableArray supportedNetworks) throws JSONException {
    JSONObject cardPaymentMethod = new JSONObject();
    cardPaymentMethod.put("type", "CARD");
    cardPaymentMethod.put(
            "parameters",
            new JSONObject()
                    .put("allowedAuthMethods", ReactNativePaymentsModule.getAllowedCardAuthMethods())
                    .put("allowedCardNetworks", ReactNativePaymentsModule.getAllowedCardNetworks(supportedNetworks)));

    return cardPaymentMethod;
  }

  private static JSONObject getCardPaymentMethod(ReadableMap methodData) throws JSONException {
      ReadableArray supportedNetworks = methodData.getArray("supportedNetworks");
      ReadableMap paymentMethodTokenizationParameters = methodData.getMap("paymentMethodTokenizationParameters");
      JSONObject cardPaymentMethod = ReactNativePaymentsModule.getBaseCardPaymentMethod(supportedNetworks);
      cardPaymentMethod.put("tokenizationSpecification", ReactNativePaymentsModule.getTokenizationSpecification(paymentMethodTokenizationParameters));

      return cardPaymentMethod;
  }

  /**
   * Provide Google Pay API with a payment amount, currency, and amount status
   *
   * @return information about the requested payment
   * @throws JSONException
   * @see <a
   *     href="https://developers.google.com/pay/api/android/reference/object#TransactionInfo">TransactionInfo</a>
   */
  private static JSONObject getTransactionInfo(ReadableMap amount) throws JSONException {

    JSONObject transactionInfo = new JSONObject();
    transactionInfo.put("totalPrice", amount.getString("value"));
    transactionInfo.put("totalPriceStatus", "FINAL");
    transactionInfo.put("currencyCode", amount.getString("currency"));

    return transactionInfo;
  }

  /**
   * Information about the merchant requesting payment information
   *
   * @return information about the merchant
   * @throws JSONException
   * @see <a
   *     href="https://developers.google.com/pay/api/android/reference/object#MerchantInfo">MerchantInfo</a>
   */
  private static JSONObject getMerchantInfo(String merchantName) throws JSONException {
    return new JSONObject()
            .put("merchantName", merchantName);
  }

  /**
   * An object describing accepted forms of payment by your app, used to determine a viewer's
   * readiness to pay
   *
   * @return API version and payment methods supported by the app
   * @see <a
   *     href="https://developers.google.com/pay/api/android/reference/object#IsReadyToPayRequest">IsReadyToPayRequest</a>
   */
  private static JSONObject getIsReadyToPayRequest(ReadableMap paymentMethodData) {
    try {
      JSONObject isReadyToPayRequest = ReactNativePaymentsModule.getBaseRequest();
      ReadableArray supportedNetworks = paymentMethodData.getArray("supportedNetworks");
      JSONObject cardPaymentMethod = ReactNativePaymentsModule.getBaseCardPaymentMethod(supportedNetworks);
      isReadyToPayRequest.put(
              "allowedPaymentMethods", new JSONArray().put(cardPaymentMethod));
      return isReadyToPayRequest;
    } catch (JSONException e) {
      Log.e("getIsReadyToPayRequest", e.toString());
      return null;
    }
  }

  private static int getEnvironment(ReadableMap paymentMethodData) {
      String methodEnvironment = paymentMethodData.getString("environment");
      return methodEnvironment.equals(new String("PRODUCTION")) ? WalletConstants.ENVIRONMENT_PRODUCTION : WalletConstants.ENVIRONMENT_TEST;
  }

  /**
   * An object describing information requested in a Google Pay payment sheet
   *
   * @return payment data expected by your app
   * @see <a
   *     href="https://developers.google.com/pay/api/android/reference/object#PaymentDataRequest">PaymentDataRequest</a>
   */
  private static JSONObject getPaymentDataRequest(ReadableMap details, ReadableMap methodData) {
      try {
          JSONObject paymentDataRequest = ReactNativePaymentsModule.getBaseRequest();
          paymentDataRequest.put(
                  "allowedPaymentMethods",
                  new JSONArray()
                          .put(ReactNativePaymentsModule.getCardPaymentMethod(methodData)));

          ReadableMap amount = details.getMap("total").getMap("amount");
          paymentDataRequest.put("transactionInfo", ReactNativePaymentsModule.getTransactionInfo(amount));

          String merchantName = details.getMap("total").getString("label");
          paymentDataRequest.put("merchantInfo", ReactNativePaymentsModule.getMerchantInfo(merchantName));

          return paymentDataRequest;
      } catch (JSONException e) {
          Log.e("getPaymentDataRequest", e.toString());
          return null;
      }
  }

  /**
   * A client for interacting with the Google Pay API
   *
   * @see <a
   *     href="https://developers.google.com/android/reference/com/google/android/gms/wallet/PaymentsClient">PaymentsClient</a>
   */
  private PaymentsClient mPaymentsClient = null;

  private Promise mRequestPaymentPromise = null;

  private final ActivityEventListener mActivityEventListener = new BaseActivityEventListener() {
    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        switch(requestCode) {
            case LOAD_PAYMENT_DATA_REQUEST_CODE:
                switch(resultCode) {
                    case Activity.RESULT_OK:
                        PaymentData paymentData = PaymentData.getFromIntent(data);
                        if(paymentData == null) {
                            mRequestPaymentPromise.reject(E_PAYMENT_DATA, "payment data is null");
                        } else {
                            String json = paymentData.toJson();
                            if (json != null) {
                                JSONObject paymentDataJson = null;
                                try {
                                    paymentDataJson = new JSONObject(json);
                                } catch (JSONException e) {
                                    mRequestPaymentPromise.reject(E_PAYMENT_DATA, e.getMessage());
                                }
                                if (paymentDataJson == null) return;
                                try {
                                    JSONObject paymentMethodData =
                                            paymentDataJson.getJSONObject("paymentMethodData");
                                    String token = paymentMethodData
                                            .getJSONObject("tokenizationData").getString("token");
                                    Log.v("Token : ", token);
                                    Log.v("Response", paymentMethodData.toString());
                                    mRequestPaymentPromise.resolve(token);
                                } catch (JSONException e) {
                                    mRequestPaymentPromise.reject(E_PAYMENT_DATA, e.getMessage());
                                }

                            } else {
                                mRequestPaymentPromise.reject(E_AUTO_RESOLVE_FAILED, "method is null");
                            }
                        }
                        break;
                    case Activity.RESULT_CANCELED:
                        mRequestPaymentPromise.reject(PAYMENT_CANCELLED, "payment has been canceled");

                        break;
                    case AutoResolveHelper.RESULT_ERROR:
                        Status status = AutoResolveHelper.getStatusFromIntent(data);
                        mRequestPaymentPromise.reject(E_AUTO_RESOLVE_FAILED, "auto resolve has been failed. status: " + status.getStatusMessage());
                        break;
                    default:
                }
                break;
            default:
        }
    }

  };

      public ReactNativePaymentsModule(ReactApplicationContext context) {
          // Pass in the context to the constructor and save it so you can emit events
          // https://facebook.github.io/react-native/docs/native-modules-android.html#the-toast-module
          super(context);

          reactContext = context;

          reactContext.addActivityEventListener(mActivityEventListener);
      }

      @Override
      public String getName() {
          // Tell React the name of the module
          // https://facebook.github.io/react-native/docs/native-modules-android.html#the-toast-module
          return REACT_CLASS;
      }

      @ReactMethod
      public void checkGPayIsEnable(ReadableMap paymentMethodData, final Promise promise) {
        final JSONObject isReadyToPayJson = ReactNativePaymentsModule.getIsReadyToPayRequest(paymentMethodData);
        if (isReadyToPayJson == null) {
            promise.reject(NOT_READY_TO_PAY, "not ready to pay");
        }
        IsReadyToPayRequest request = IsReadyToPayRequest.fromJson(isReadyToPayJson.toString());
        if (request == null) {
            promise.reject(NOT_READY_TO_PAY, "not ready to pay");
        }

        Activity activity = getCurrentActivity();

        if (activity == null) {
            promise.reject(E_NO_ACTIVITY, "activity is null");
        }

        Task<Boolean> task = getPaymentsClient(ReactNativePaymentsModule.getEnvironment(paymentMethodData), activity).isReadyToPay(request);
        task.addOnCompleteListener(
                new OnCompleteListener<Boolean>() {
                    @Override
                    public void onComplete(@NonNull Task<Boolean> task) {
                        try {
                            boolean result = task.getResult(ApiException.class);
                            if (result) {
                                promise.resolve(result);
                            } else {
                                promise.reject(NOT_READY_TO_PAY, "not ready to pay");
                            }
                        } catch (ApiException exception) {
                            promise.reject(E_FAILED_TO_DETECT_IF_READY, exception.getMessage());
                        }
                    }
                });
      }


      @ReactMethod
      public void show(
              ReadableMap paymentMethodData,
              ReadableMap details,
              final Promise promise
      ) {

          Activity activity = getCurrentActivity();

          if (activity == null) {
              promise.reject(E_NO_ACTIVITY, "activity is null");
              return;
          }

          this.mRequestPaymentPromise = promise;

          JSONObject paymentDataRequest = ReactNativePaymentsModule.getPaymentDataRequest(details, paymentMethodData);
          if (paymentDataRequest == null) {
              promise.reject(E_NO_PAYMENT_REQUEST_JSON, "payment data request json is null");
              return;
          }

          PaymentDataRequest request = PaymentDataRequest.fromJson(paymentDataRequest.toString());

          if (request != null) {
              AutoResolveHelper.resolveTask(
                      getPaymentsClient(ReactNativePaymentsModule.getEnvironment(paymentMethodData), activity).loadPaymentData(request), activity, LOAD_PAYMENT_DATA_REQUEST_CODE);
          } else {
              promise.reject(E_NO_PAYMENT_REQUEST, "payment data request is null");
          }
      }

      private PaymentsClient getPaymentsClient(int environment, @NonNull Activity activity) {

          if (mPaymentsClient == null) {
              mPaymentsClient =
                      Wallet.getPaymentsClient(
                              activity,
                              new Wallet.WalletOptions.Builder()
                                      .setEnvironment(environment)
                                      .build());
          }

          return mPaymentsClient;
      }

  }

