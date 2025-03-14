/*!
 * Copyright 2010 - 2018 Hitachi Vantara.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.pentaho.caching.impl;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import org.pentaho.caching.api.PentahoCacheManager;
import org.pentaho.caching.api.PentahoCacheProvidingService;
import org.pentaho.caching.api.PentahoCacheSystemConfiguration;

import java.text.MessageFormat;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.pentaho.caching.api.Constants.PENTAHO_CACHE_PROVIDER;
import static org.pentaho.caching.api.Constants.convertDictionary;

/**
 * @author nhudak
 */
public class PentahoCacheManagerFactory {

    private final ListMultimap<String, SettableFuture<PentahoCacheProvidingService>> providerMap;
    private final Map<String, RegistrationHandler> registrationHandlerMap;

    public PentahoCacheManagerFactory(  ) {
        providerMap = LinkedListMultimap.create();
        registrationHandlerMap = Maps.newHashMap();
    }

    public void init() {
            PentahoCacheProvidingService service = new Ehcache
            registerProvider( providerId, service );

    }

    @Override public String getName() {
        return "Pentaho Cache Manager Factory";
    }

    protected synchronized ListenableFuture<PentahoCacheProvidingService> getProviderService( String providerId ) {
        List<SettableFuture<PentahoCacheProvidingService>> futureList = providerMap.get( providerId );
        if ( futureList.isEmpty() ) {
            futureList.add( SettableFuture.<PentahoCacheProvidingService>create() );
        }
        return futureList.get( 0 );
    }

    @Override public synchronized void updated( final String pid, Dictionary<String, ?> dictionary )
            throws ConfigurationException {
        Map<String, String> properties = convertDictionary( dictionary );

        String providerId = properties.get( PENTAHO_CACHE_PROVIDER );
        if ( providerId == null ) {
            throw new ConfigurationException( PENTAHO_CACHE_PROVIDER, "required property not specified" );
        }

        RegistrationHandler registrationHandler = registrationHandlerMap.get( pid );
        if ( registrationHandler == null ) {
            // New configuration
            registrationHandler = new RegistrationHandler( pid, providerId, new PentahoCacheSystemConfiguration() );
            registrationHandler.config.setData( properties );
            registrationHandler.startService();
        } else if ( providerId.equals( registrationHandler.providerId ) ) {
            // Existing pid configuration
            registrationHandler.config.setData( properties );
        } else {
            // Service changed for existing pid configuration
            registrationHandler.config.setData( properties );
            registrationHandler = restartService( pid, providerId );
        }
        registrationHandlerMap.put( pid, registrationHandler );
    }

    public synchronized void registerProvider( String id, PentahoCacheProvidingService provider ) {
        List<SettableFuture<PentahoCacheProvidingService>> futureList = providerMap.get( id );
        if ( futureList.isEmpty() ) {
            futureList.add( SettableFuture.<PentahoCacheProvidingService>create() );
        }
        for ( SettableFuture<PentahoCacheProvidingService> future : futureList ) {
            future.set( provider );
        }
    }

    @Override public synchronized void deleted( String pid ) {
        RegistrationHandler registrationHandler = registrationHandlerMap.remove( pid );
        if ( registrationHandler == null ) {
            Logger.getLogger( getName() ).log( Level.WARNING, "Attempted to delete unused pid: " + pid );
        } else {
            registrationHandler.shutdownService();
        }
    }

    public synchronized void unregisterProvider( String providerId, PentahoCacheProvidingService provider ) {
        Set<ListenableFuture<PentahoCacheProvidingService>> invalidFutures = Sets.newHashSet();
        for ( Iterator<SettableFuture<PentahoCacheProvidingService>> iterator = providerMap.get( providerId ).iterator();
              iterator.hasNext(); ) {
            SettableFuture<PentahoCacheProvidingService> future = iterator.next();
            try {
                if ( future.isDone() && future.get( 10, TimeUnit.SECONDS ).equals( provider ) ) {
                    iterator.remove();
                    invalidFutures.add( future );
                }
            } catch ( Throwable t ) {
                Logger.getLogger( providerId ).log( Level.WARNING, "Unexpected exception", t );
            }
        }

        Set<String> pidSet = Sets.newHashSet();
        Iterator<RegistrationHandler> registrations = registrationHandlerMap.values().iterator();
        while ( !invalidFutures.isEmpty() && registrations.hasNext() ) {
            RegistrationHandler registrationHandler = registrations.next();
            if ( invalidFutures.contains( registrationHandler.serviceFuture ) ) {
                pidSet.add( registrationHandler.pid );
            }
        }

        for ( String pid : pidSet ) {
            restartService( pid, providerId );
        }
    }

    private synchronized RegistrationHandler restartService( String pid, String providerId ) {
        RegistrationHandler startup, shutdown;
        shutdown = registrationHandlerMap.remove( pid );
        startup = new RegistrationHandler( pid, providerId, shutdown.config );
        registrationHandlerMap.put( pid, startup );

        shutdown.shutdownService();
        startup.startService();
        return startup;
    }

    private class RegistrationHandler {
        private final String pid;
        private final String providerId;
        private final PentahoCacheSystemConfiguration config;
        private final ListenableFuture<PentahoCacheProvidingService> serviceFuture;
        private final SettableFuture<ServiceRegistration<?>> registrationFuture;
        private final Logger logger;

        public RegistrationHandler( String pid, String providerId, PentahoCacheSystemConfiguration systemConfiguration ) {
            this.pid = pid;
            this.providerId = providerId;
            this.serviceFuture = getProviderService( providerId );
            config = systemConfiguration;
            registrationFuture = SettableFuture.create();
            logger = Logger.getLogger( pid );
        }

        void startService() {
            Futures.addCallback( serviceFuture, new FutureCallback<PentahoCacheProvidingService>() {
                @Override public void onSuccess( PentahoCacheProvidingService providingService ) {
                    if ( !registrationFuture.isDone() ) {
                        PentahoCacheManager cacheManager = new PentahoCacheManagerImpl( config, providingService );

                        Hashtable<String, Object> serviceProperties = new Hashtable<String, Object>();
                        serviceProperties.put( PENTAHO_CACHE_PROVIDER, providerId );
                        serviceProperties.put( SERVICE_PID, pid );

                        ServiceRegistration<PentahoCacheManager> registration =
                                bundleContext.registerService( PentahoCacheManager.class, cacheManager, serviceProperties );

                        registrationFuture.set( registration );
                        logger.log( Level.INFO, "New Caching Service registered" );
                    }
                }

                @Override public void onFailure( Throwable t ) {
                    logger.log( Level.WARNING, "Caching Service startup failed", t );
                }
            }, MoreExecutors.newDirectExecutorService() );
        }

        public void shutdownService() {
            registrationFuture.cancel( false );
            Futures.addCallback( registrationFuture, new FutureCallback<ServiceRegistration<?>>() {
                @Override public void onSuccess( ServiceRegistration<?> registration ) {
                    registration.unregister();
                    logger.log( Level.INFO, "Caching Service was shutdown" );
                }

                @Override public void onFailure( Throwable t ) {
                    if ( t instanceof CancellationException ) {
                        logger.log( Level.INFO, "Caching Service was disabled" );
                    } else {
                        logger.log( Level.WARNING, "Caching Service shutdown failed", t );
                    }
                }
            }, MoreExecutors.newDirectExecutorService() );
        }
    }
}
