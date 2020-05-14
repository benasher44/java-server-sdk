/**
 * The package for interfaces that allow customization of LaunchDarkly components, and interfaces
 * to other advanced SDK features. 
 * <p>
 * Most application will not need to refer to these types. You will use them if you are creating a
 * plug-in component, such as a database integration, or if you use advanced features such as
 * {@link com.launchdarkly.sdk.server.LDClientInterface#getDataStoreStatusProvider()} or
 * {@link com.launchdarkly.sdk.server.LDClientInterface#registerFlagChangeListener(FlagChangeListener)}.
 */
package com.launchdarkly.sdk.server.interfaces;