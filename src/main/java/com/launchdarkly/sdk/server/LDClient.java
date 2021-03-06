package com.launchdarkly.sdk.server;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.server.interfaces.DataSource;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataSourceUpdates;
import com.launchdarkly.sdk.server.interfaces.DataStore;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.interfaces.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.interfaces.Event;
import com.launchdarkly.sdk.server.interfaces.EventProcessor;
import com.launchdarkly.sdk.server.interfaces.FlagChangeEvent;
import com.launchdarkly.sdk.server.interfaces.FlagChangeListener;
import com.launchdarkly.sdk.server.interfaces.FlagTracker;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import org.apache.commons.codec.binary.Hex;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.launchdarkly.sdk.EvaluationDetail.NO_VARIATION;
import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;

/**
 * A client for the LaunchDarkly API. Client instances are thread-safe. Applications should instantiate
 * a single {@code LDClient} for the lifetime of their application.
 */
public final class LDClient implements LDClientInterface {
  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final String sdkKey;
  private final boolean offline;
  private final Evaluator evaluator;
  final EventProcessor eventProcessor;
  final DataSource dataSource;
  final DataStore dataStore;
  private final DataSourceUpdates dataSourceUpdates;
  private final DataStoreStatusProviderImpl dataStoreStatusProvider;
  private final DataSourceStatusProviderImpl dataSourceStatusProvider;
  private final FlagTrackerImpl flagTracker;
  private final EventBroadcasterImpl<FlagChangeListener, FlagChangeEvent> flagChangeBroadcaster;
  private final ScheduledExecutorService sharedExecutor;
  private final EventFactory eventFactoryDefault;
  private final EventFactory eventFactoryWithReasons;
  
  /**
   * Creates a new client instance that connects to LaunchDarkly with the default configuration.
   * <p>
   * If you need to specify any custom SDK options, use {@link LDClient#LDClient(String, LDConfig)}
   * instead.
   * <p>
   * Applications should instantiate a single instance for the lifetime of the application. In
   * unusual cases where an application needs to evaluate feature flags from different LaunchDarkly
   * projects or environments, you may create multiple clients, but they should still be retained
   * for the lifetime of the application rather than created per request or per thread.
   * <p>
   * The client will begin attempting to connect to LaunchDarkly as soon as you call the constructor.
   * The constructor will return when it successfully connects, or when the default timeout of 5 seconds
   * expires, whichever comes first. If it has not succeeded in connecting when the timeout elapses,
   * you will receive the client in an uninitialized state where feature flags will return default
   * values; it will still continue trying to connect in the background. You can detect whether
   * initialization has succeeded by calling {@link #isInitialized()}. If you prefer to customize
   * this behavior, use {@link LDClient#LDClient(String, LDConfig)} instead.
   *
   * @param sdkKey the SDK key for your LaunchDarkly environment
   * @see LDClient#LDClient(String, LDConfig)
   */
  public LDClient(String sdkKey) {
    // COVERAGE: this constructor cannot be called in unit tests because it uses the default base
    // URI and will attempt to make a live connection to LaunchDarkly.
    this(sdkKey, LDConfig.DEFAULT);
  }

  private static final DataModel.FeatureFlag getFlag(DataStore store, String key) {
    ItemDescriptor item = store.get(FEATURES, key);
    return item == null ? null : (DataModel.FeatureFlag)item.getItem();
  }
  
  private static final DataModel.Segment getSegment(DataStore store, String key) {
    ItemDescriptor item = store.get(SEGMENTS, key);
    return item == null ? null : (DataModel.Segment)item.getItem();
  }
  
  /**
   * Creates a new client to connect to LaunchDarkly with a custom configuration.
   * <p>
   * This constructor can be used to configure advanced SDK features; see {@link LDConfig.Builder}.
   * <p>
   * Applications should instantiate a single instance for the lifetime of the application. In
   * unusual cases where an application needs to evaluate feature flags from different LaunchDarkly
   * projects or environments, you may create multiple clients, but they should still be retained
   * for the lifetime of the application rather than created per request or per thread.
   * <p>
   * Unless it is configured to be offline with {@link LDConfig.Builder#offline(boolean)} or
   * {@link Components#externalUpdatesOnly()}, the client will begin attempting to connect to
   * LaunchDarkly as soon as you call the constructor. The constructor will return when it successfully
   * connects, or when the timeout set by {@link LDConfig.Builder#startWait(java.time.Duration)} (default:
   * 5 seconds) expires, whichever comes first. If it has not succeeded in connecting when the timeout
   * elapses, you will receive the client in an uninitialized state where feature flags will return
   * default values; it will still continue trying to connect in the background. You can detect
   * whether initialization has succeeded by calling {@link #isInitialized()}.
   * <p>
   * If you prefer to have the constructor return immediately, and then wait for initialization to finish
   * at some other point, you can use {@link #getDataSourceStatusProvider()} as follows:
   * <pre><code>
   *     LDConfig config = new LDConfig.Builder()
   *         .startWait(Duration.ZERO)
   *         .build();
   *     LDClient client = new LDClient(sdkKey, config);
   *     
   *     // later, when you want to wait for initialization to finish:
   *     boolean inited = client.getDataSourceStatusProvider().waitFor(
   *         DataSourceStatusProvider.State.VALID, Duration.ofSeconds(10));
   *     if (!inited) {
   *         // do whatever is appropriate if initialization has timed out
   *     }
   * </code></pre>
   *
   * @param sdkKey the SDK key for your LaunchDarkly environment
   * @param config a client configuration object
   * @see LDClient#LDClient(String, LDConfig)
   */
  public LDClient(String sdkKey, LDConfig config) {
    checkNotNull(config, "config must not be null");
    this.sdkKey = checkNotNull(sdkKey, "sdkKey must not be null");
    this.offline = config.offline;
    
    this.sharedExecutor = createSharedExecutor(config);
    
    boolean eventsDisabled = Components.isNullImplementation(config.eventProcessorFactory);
    if (eventsDisabled) {
      this.eventFactoryDefault = EventFactory.Disabled.INSTANCE;
      this.eventFactoryWithReasons = EventFactory.Disabled.INSTANCE;
    } else {
      this.eventFactoryDefault = EventFactory.DEFAULT;
      this.eventFactoryWithReasons = EventFactory.DEFAULT_WITH_REASONS;
    }
    
    // Do not create diagnostic accumulator if config has specified is opted out, or if we're not using the
    // standard event processor
    final boolean useDiagnostics = !config.diagnosticOptOut && config.eventProcessorFactory instanceof EventProcessorBuilder;
    final ClientContextImpl context = new ClientContextImpl(
        sdkKey,
        config,
        sharedExecutor,
        useDiagnostics ? new DiagnosticAccumulator(new DiagnosticId(sdkKey)) : null
        );

    this.eventProcessor = config.eventProcessorFactory.createEventProcessor(context);

    EventBroadcasterImpl<DataStoreStatusProvider.StatusListener, DataStoreStatusProvider.Status> dataStoreStatusNotifier =
        EventBroadcasterImpl.forDataStoreStatus(sharedExecutor);
    DataStoreUpdatesImpl dataStoreUpdates = new DataStoreUpdatesImpl(dataStoreStatusNotifier);
    this.dataStore = config.dataStoreFactory.createDataStore(context, dataStoreUpdates);

    this.evaluator = new Evaluator(new Evaluator.Getters() {
      public DataModel.FeatureFlag getFlag(String key) {
        return LDClient.getFlag(LDClient.this.dataStore, key);
      }

      public DataModel.Segment getSegment(String key) {
        return LDClient.getSegment(LDClient.this.dataStore, key);
      }
    });

    this.flagChangeBroadcaster = EventBroadcasterImpl.forFlagChangeEvents(sharedExecutor);
    this.flagTracker = new FlagTrackerImpl(flagChangeBroadcaster,
        (key, user) -> jsonValueVariation(key, user, LDValue.ofNull()));

    this.dataStoreStatusProvider = new DataStoreStatusProviderImpl(this.dataStore, dataStoreUpdates);

    EventBroadcasterImpl<DataSourceStatusProvider.StatusListener, DataSourceStatusProvider.Status> dataSourceStatusNotifier =
        EventBroadcasterImpl.forDataSourceStatus(sharedExecutor);
    DataSourceUpdatesImpl dataSourceUpdates = new DataSourceUpdatesImpl(
        dataStore,
        dataStoreStatusProvider,
        flagChangeBroadcaster,
        dataSourceStatusNotifier,
        sharedExecutor,
        context.getLogging().getLogDataSourceOutageAsErrorAfter()
        );
    this.dataSourceUpdates = dataSourceUpdates;
    this.dataSource = config.dataSourceFactory.createDataSource(context, dataSourceUpdates);    
    this.dataSourceStatusProvider = new DataSourceStatusProviderImpl(dataSourceStatusNotifier, dataSourceUpdates);
    
    Future<Void> startFuture = dataSource.start();
    if (!config.startWait.isZero() && !config.startWait.isNegative()) {
      if (!(dataSource instanceof ComponentsImpl.NullDataSource)) {
        Loggers.MAIN.info("Waiting up to " + config.startWait.toMillis() + " milliseconds for LaunchDarkly client to start...");
      }
      try {
        startFuture.get(config.startWait.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        Loggers.MAIN.error("Timeout encountered waiting for LaunchDarkly client initialization");
      } catch (Exception e) {
        Loggers.MAIN.error("Exception encountered waiting for LaunchDarkly client initialization: {}", e.toString());
        Loggers.MAIN.debug(e.toString(), e);
      }
      if (!dataSource.isInitialized()) {
        Loggers.MAIN.warn("LaunchDarkly client was not successfully initialized");
      }
    }
  }

  @Override
  public boolean isInitialized() {
    return dataSource.isInitialized();
  }

  @Override
  public void track(String eventName, LDUser user) {
    trackData(eventName, user, LDValue.ofNull());
  }

  @Override
  public void trackData(String eventName, LDUser user, LDValue data) {
    if (user == null || user.getKey() == null) {
      Loggers.MAIN.warn("Track called with null user or null user key!");
    } else {
      eventProcessor.sendEvent(eventFactoryDefault.newCustomEvent(eventName, user, data, null));
    }
  }

  @Override
  public void trackMetric(String eventName, LDUser user, LDValue data, double metricValue) {
    if (user == null || user.getKey() == null) {
      Loggers.MAIN.warn("Track called with null user or null user key!");
    } else {
      eventProcessor.sendEvent(eventFactoryDefault.newCustomEvent(eventName, user, data, metricValue));
    }
  }

  @Override
  public void identify(LDUser user) {
    if (user == null || user.getKey() == null) {
      Loggers.MAIN.warn("Identify called with null user or null user key!");
    } else {
      eventProcessor.sendEvent(eventFactoryDefault.newIdentifyEvent(user));
    }
  }

  private void sendFlagRequestEvent(Event.FeatureRequest event) {
    if (event != null) {
      eventProcessor.sendEvent(event);
    }
  }
  
  @Override
  public FeatureFlagsState allFlagsState(LDUser user, FlagsStateOption... options) {
    FeatureFlagsState.Builder builder = new FeatureFlagsState.Builder(options);
    
    if (isOffline()) {
      Loggers.EVALUATION.debug("allFlagsState() was called when client is in offline mode.");
    }
    
    if (!isInitialized()) {
      if (dataStore.isInitialized()) {
        Loggers.EVALUATION.warn("allFlagsState() was called before client initialized; using last known values from data store");
      } else {
        Loggers.EVALUATION.warn("allFlagsState() was called before client initialized; data store unavailable, returning no data");
        return builder.valid(false).build();
      }
    }

    if (user == null || user.getKey() == null) {
      Loggers.EVALUATION.warn("allFlagsState() was called with null user or null user key! returning no data");
      return builder.valid(false).build();
    }

    boolean clientSideOnly = FlagsStateOption.hasOption(options, FlagsStateOption.CLIENT_SIDE_ONLY);
    KeyedItems<ItemDescriptor> flags;
    try {
      flags = dataStore.getAll(FEATURES);
    } catch (Exception e) {
      Loggers.EVALUATION.error("Exception from data store when evaluating all flags: {}", e.toString());
      Loggers.EVALUATION.debug(e.toString(), e);
      return builder.valid(false).build();
    }
    
    for (Map.Entry<String, ItemDescriptor> entry : flags.getItems()) {
      if (entry.getValue().getItem() == null) {
        continue; // deleted flag placeholder
      }
      DataModel.FeatureFlag flag = (DataModel.FeatureFlag)entry.getValue().getItem();
      if (clientSideOnly && !flag.isClientSide()) {
        continue;
      }
      try {
        Evaluator.EvalResult result = evaluator.evaluate(flag, user, eventFactoryDefault);
        builder.addFlag(flag, result);
      } catch (Exception e) {
        Loggers.EVALUATION.error("Exception caught for feature flag \"{}\" when evaluating all flags: {}", entry.getKey(), e.toString());
        Loggers.EVALUATION.debug(e.toString(), e);
        builder.addFlag(flag, new Evaluator.EvalResult(LDValue.ofNull(), NO_VARIATION, EvaluationReason.exception(e)));
      }
    }
    return builder.build();
  }
  
  @Override
  public boolean boolVariation(String featureKey, LDUser user, boolean defaultValue) {
    return evaluate(featureKey, user, LDValue.of(defaultValue), true).booleanValue();
  }

  @Override
  public int intVariation(String featureKey, LDUser user, int defaultValue) {
    return evaluate(featureKey, user, LDValue.of(defaultValue), true).intValue();
  }

  @Override
  public double doubleVariation(String featureKey, LDUser user, double defaultValue) {
    return evaluate(featureKey, user, LDValue.of(defaultValue), true).doubleValue();
  }

  @Override
  public String stringVariation(String featureKey, LDUser user, String defaultValue) {
    return evaluate(featureKey, user, LDValue.of(defaultValue), true).stringValue();
  }

  @Override
  public LDValue jsonValueVariation(String featureKey, LDUser user, LDValue defaultValue) {
    return evaluate(featureKey, user, LDValue.normalize(defaultValue), false);
  }

  @Override
  public EvaluationDetail<Boolean> boolVariationDetail(String featureKey, LDUser user, boolean defaultValue) {
    Evaluator.EvalResult result = evaluateInternal(featureKey, user, LDValue.of(defaultValue), true,
        eventFactoryWithReasons);
     return EvaluationDetail.fromValue(result.getValue().booleanValue(),
         result.getVariationIndex(), result.getReason());
  }

  @Override
  public EvaluationDetail<Integer> intVariationDetail(String featureKey, LDUser user, int defaultValue) {
    Evaluator.EvalResult result = evaluateInternal(featureKey, user, LDValue.of(defaultValue), true,
        eventFactoryWithReasons);
    return EvaluationDetail.fromValue(result.getValue().intValue(),
        result.getVariationIndex(), result.getReason());
  }

  @Override
  public EvaluationDetail<Double> doubleVariationDetail(String featureKey, LDUser user, double defaultValue) {
    Evaluator.EvalResult result = evaluateInternal(featureKey, user, LDValue.of(defaultValue), true,
        eventFactoryWithReasons);
    return EvaluationDetail.fromValue(result.getValue().doubleValue(),
        result.getVariationIndex(), result.getReason());
  }

  @Override
  public EvaluationDetail<String> stringVariationDetail(String featureKey, LDUser user, String defaultValue) {
    Evaluator.EvalResult result = evaluateInternal(featureKey, user, LDValue.of(defaultValue), true,
        eventFactoryWithReasons);
    return EvaluationDetail.fromValue(result.getValue().stringValue(),
        result.getVariationIndex(), result.getReason());
  }

  @Override
  public EvaluationDetail<LDValue> jsonValueVariationDetail(String featureKey, LDUser user, LDValue defaultValue) {
    Evaluator.EvalResult result = evaluateInternal(featureKey, user, LDValue.normalize(defaultValue), false, eventFactoryWithReasons);
    return EvaluationDetail.fromValue(result.getValue(), result.getVariationIndex(), result.getReason());
  }
  
  @Override
  public boolean isFlagKnown(String featureKey) {
    if (!isInitialized()) {
      if (dataStore.isInitialized()) {
        Loggers.MAIN.warn("isFlagKnown called before client initialized for feature flag \"{}\"; using last known values from data store", featureKey);
      } else {
        Loggers.MAIN.warn("isFlagKnown called before client initialized for feature flag \"{}\"; data store unavailable, returning false", featureKey);
        return false;
      }
    }

    try {
      if (getFlag(dataStore, featureKey) != null) {
        return true;
      }
    } catch (Exception e) {
      Loggers.MAIN.error("Encountered exception while calling isFlagKnown for feature flag \"{}\": {}", e.toString());
      Loggers.MAIN.debug(e.toString(), e);
    }

    return false;
  }

  private LDValue evaluate(String featureKey, LDUser user, LDValue defaultValue, boolean checkType) {
    return evaluateInternal(featureKey, user, defaultValue, checkType, eventFactoryDefault).getValue();
  }
  
  private Evaluator.EvalResult errorResult(EvaluationReason.ErrorKind errorKind, final LDValue defaultValue) {
    return new Evaluator.EvalResult(defaultValue, NO_VARIATION, EvaluationReason.error(errorKind));
  }
  
  private Evaluator.EvalResult evaluateInternal(String featureKey, LDUser user, LDValue defaultValue, boolean checkType,
      EventFactory eventFactory) {
    if (!isInitialized()) {
      if (dataStore.isInitialized()) {
        Loggers.EVALUATION.warn("Evaluation called before client initialized for feature flag \"{}\"; using last known values from data store", featureKey);
      } else {
        Loggers.EVALUATION.warn("Evaluation called before client initialized for feature flag \"{}\"; data store unavailable, returning default value", featureKey);
        sendFlagRequestEvent(eventFactory.newUnknownFeatureRequestEvent(featureKey, user, defaultValue,
            EvaluationReason.ErrorKind.CLIENT_NOT_READY));
        return errorResult(EvaluationReason.ErrorKind.CLIENT_NOT_READY, defaultValue);
      }
    }

    DataModel.FeatureFlag featureFlag = null;
    try {
      featureFlag = getFlag(dataStore, featureKey);
      if (featureFlag == null) {
        Loggers.EVALUATION.info("Unknown feature flag \"{}\"; returning default value", featureKey);
        sendFlagRequestEvent(eventFactory.newUnknownFeatureRequestEvent(featureKey, user, defaultValue,
            EvaluationReason.ErrorKind.FLAG_NOT_FOUND));
        return errorResult(EvaluationReason.ErrorKind.FLAG_NOT_FOUND, defaultValue);
      }
      if (user == null || user.getKey() == null) {
        Loggers.EVALUATION.warn("Null user or null user key when evaluating flag \"{}\"; returning default value", featureKey);
        sendFlagRequestEvent(eventFactory.newDefaultFeatureRequestEvent(featureFlag, user, defaultValue,
            EvaluationReason.ErrorKind.USER_NOT_SPECIFIED));
        return errorResult(EvaluationReason.ErrorKind.USER_NOT_SPECIFIED, defaultValue);
      }
      if (user.getKey().isEmpty()) {
        Loggers.EVALUATION.warn("User key is blank. Flag evaluation will proceed, but the user will not be stored in LaunchDarkly");
      }
      Evaluator.EvalResult evalResult = evaluator.evaluate(featureFlag, user, eventFactory);
      for (Event.FeatureRequest event : evalResult.getPrerequisiteEvents()) {
        eventProcessor.sendEvent(event);
      }
      if (evalResult.isDefault()) {
        evalResult.setValue(defaultValue);
      } else {
        LDValue value = evalResult.getValue(); // guaranteed not to be an actual Java null, but can be LDValue.ofNull()
        if (checkType && !value.isNull() && !defaultValue.isNull() && defaultValue.getType() != value.getType()) {
          Loggers.EVALUATION.error("Feature flag evaluation expected result as {}, but got {}", defaultValue.getType(), value.getType());
          sendFlagRequestEvent(eventFactory.newUnknownFeatureRequestEvent(featureKey, user, defaultValue,
              EvaluationReason.ErrorKind.WRONG_TYPE));
          return errorResult(EvaluationReason.ErrorKind.WRONG_TYPE, defaultValue);
        }
      }
      sendFlagRequestEvent(eventFactory.newFeatureRequestEvent(featureFlag, user, evalResult, defaultValue));
      return evalResult;
    } catch (Exception e) {
      Loggers.EVALUATION.error("Encountered exception while evaluating feature flag \"{}\": {}", featureKey, e.toString());
      Loggers.EVALUATION.debug(e.toString(), e);
      if (featureFlag == null) {
        sendFlagRequestEvent(eventFactory.newUnknownFeatureRequestEvent(featureKey, user, defaultValue,
            EvaluationReason.ErrorKind.EXCEPTION));
      } else {
        sendFlagRequestEvent(eventFactory.newDefaultFeatureRequestEvent(featureFlag, user, defaultValue,
            EvaluationReason.ErrorKind.EXCEPTION));
      }
      return new Evaluator.EvalResult(defaultValue, NO_VARIATION, EvaluationReason.exception(e));
    }
  }

  @Override
  public FlagTracker getFlagTracker() {
    return flagTracker;
  }
  
  @Override
  public DataStoreStatusProvider getDataStoreStatusProvider() {
    return dataStoreStatusProvider;
  }

  @Override
  public DataSourceStatusProvider getDataSourceStatusProvider() {
    return dataSourceStatusProvider;
  }
  
  @Override
  public void close() throws IOException {
    Loggers.MAIN.info("Closing LaunchDarkly Client");
    this.dataStore.close();
    this.eventProcessor.close();
    this.dataSource.close();
    this.dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.OFF, null);
    this.sharedExecutor.shutdownNow();
  }

  @Override
  public void flush() {
    this.eventProcessor.flush();
  }

  @Override
  public boolean isOffline() {
    return offline;
  }

  @Override
  public String secureModeHash(LDUser user) {
    if (user == null || user.getKey() == null) {
      return null;
    }
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(sdkKey.getBytes(), HMAC_ALGORITHM));
      return Hex.encodeHexString(mac.doFinal(user.getKey().getBytes("UTF8")));
    } catch (InvalidKeyException | UnsupportedEncodingException | NoSuchAlgorithmException e) {
      // COVERAGE: there is no way to cause these errors in a unit test.
      Loggers.MAIN.error("Could not generate secure mode hash: {}", e.toString());
      Loggers.MAIN.debug(e.toString(), e);
    }
    return null;
  }

  /**
   * Returns the current version string of the client library.
   * @return a version string conforming to Semantic Versioning (http://semver.org)
   */
  @Override
  public String version() {
    return Version.SDK_VERSION;
  }
  
  // This executor is used for a variety of SDK tasks such as flag change events, checking the data store
  // status after an outage, and the poll task in polling mode. These are all tasks that we do not expect
  // to be executing frequently so that it is acceptable to use a single thread to execute them one at a
  // time rather than a thread pool, thus reducing the number of threads spawned by the SDK. This also
  // has the benefit of producing predictable delivery order for event listener notifications.
  private ScheduledExecutorService createSharedExecutor(LDConfig config) {
    ThreadFactory threadFactory = new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat("LaunchDarkly-tasks-%d")
        .setPriority(config.threadPriority)
        .build();
    return Executors.newSingleThreadScheduledExecutor(threadFactory);
  }
}
