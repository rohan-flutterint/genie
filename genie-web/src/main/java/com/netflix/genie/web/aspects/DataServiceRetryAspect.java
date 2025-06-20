/*
 *
 *  Copyright 2016 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.genie.web.aspects;

import com.google.common.collect.ImmutableMap;
import com.netflix.genie.common.exceptions.GenieException;
import com.netflix.genie.common.internal.exceptions.checked.GenieCheckedException;
import com.netflix.genie.common.internal.exceptions.unchecked.GenieRuntimeException;
import com.netflix.genie.web.properties.DataServiceRetryProperties;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import jakarta.validation.ConstraintViolationException;

/**
 * Aspect implementation of retrying the data service methods on certain failures.
 *
 * @author amajumdar
 * @since 3.0.0
 */
@Aspect
@Slf4j
public class DataServiceRetryAspect implements Ordered {
    private final RetryTemplate retryTemplate;

    /**
     * Constructor.
     *
     * @param dataServiceRetryProperties retry properties
     */
    public DataServiceRetryAspect(final DataServiceRetryProperties dataServiceRetryProperties) {
        this.retryTemplate = new RetryTemplate();
        this.retryTemplate.setRetryPolicy(
            new SimpleRetryPolicy(
                dataServiceRetryProperties.getNoOfRetries(),
                new ImmutableMap.Builder<Class<? extends Throwable>, Boolean>()
                    .put(CannotGetJdbcConnectionException.class, true)
                    .put(CannotAcquireLockException.class, true)
                    .put(DeadlockLoserDataAccessException.class, true)
                    .put(OptimisticLockingFailureException.class, true)
                    .put(PessimisticLockingFailureException.class, true)
                    .put(ConcurrencyFailureException.class, true)
                    // Will this work for cases where the write queries timeout on the client?
                    .put(QueryTimeoutException.class, true)
                    .put(TransientDataAccessResourceException.class, true)
                    .put(JpaSystemException.class, true)
                    .build()
            )
        );
        final ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(dataServiceRetryProperties.getInitialInterval());
        backOffPolicy.setMaxInterval(dataServiceRetryProperties.getMaxInterval());
        this.retryTemplate.setBackOffPolicy(backOffPolicy);
    }

    /**
     * Sets the retry listeners for the retry template in use.
     *
     * @param retryListeners retry listeners
     */
    public void setRetryListeners(final RetryListener[] retryListeners) {
        this.retryTemplate.setListeners(retryListeners);
    }

    /**
     * Aspect implementation method of retrying the data service method on certain failures.
     *
     * @param pjp join point
     * @return return the data method response
     * @throws GenieException        any exception thrown by the data service method
     * @throws GenieCheckedException any exception thrown by one of the data service methods Genie code
     */
    @Around("com.netflix.genie.web.aspects.SystemArchitecture.dataOperation()")
    public Object profile(final ProceedingJoinPoint pjp) throws GenieException, GenieCheckedException {
        try {
            return retryTemplate.execute(context -> pjp.proceed());
        } catch (
            GenieException
                | GenieCheckedException
                | GenieRuntimeException
                | ConstraintViolationException e
        ) {
            throw e;
        } catch (Throwable e) {
            throw new GenieRuntimeException("Failed to execute data service method due to " + e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrder() {
        // Currently setting this to 0 since we want the retry to happen before the transaction interceptor.
        return 0;
    }
}
