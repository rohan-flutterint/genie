/*
 *
 *  Copyright 2020 Netflix, Inc.
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
package com.netflix.genie.web.spring.actuators;

import com.google.common.collect.ImmutableMap;
import com.netflix.genie.web.services.ClusterLeaderService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;


/**
 * An actuator endpoint that exposes leadership status and allows stop/start/restart of the leader election service.
 * Useful when a specific set of nodes should be given priority to win the leader election (e.g., because they are
 * running newer code).
 *
 * @author mprimi
 * @since 4.0.0
 */
@Endpoint(id = "leaderElection")
@Slf4j
public class LeaderElectionActuator {

    private static final String RUNNING = "running";
    private static final String LEADER = "leader";
    private final ClusterLeaderService clusterLeaderService;

    /**
     * Constructor.
     *
     * @param clusterLeaderService the cluster leader service
     */
    public LeaderElectionActuator(final ClusterLeaderService clusterLeaderService) {
        this.clusterLeaderService = clusterLeaderService;
    }

    /**
     * Provides the current leader service status: whether the leader service is running and whether the node is leader.
     *
     * @return a map of attributes
     */
    @ReadOperation
    public Map<String, Object> getStatus() {
        return ImmutableMap.<String, Object>builder()
            .put(RUNNING, this.clusterLeaderService.isRunning())
            .put(LEADER, this.clusterLeaderService.isLeader())
            .build();
    }

    /**
     * Forces the node to perform leader election related actions.
     * This method uses direct request access to retrieve parameters,
     * avoiding issues with parameter name resolution when compiled without the '-parameters' flag.
     * <p>
     * Required request parameter:
     * - action: The action to perform. Must be one of: START, STOP, RESTART
     * <p>
     * Example usage:
     * POST /actuator/leaderElection?action=RESTART
     *
     * @throws IllegalStateException if no request context is available
     * @throws IllegalArgumentException if the action parameter is missing or invalid
     * @throws UnsupportedOperationException if the action is not supported
     * @throws RuntimeException if there is an error executing the requested action
     */
    @WriteOperation
    public void doAction() {
        final ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new IllegalStateException("No request context available");
        }

        final HttpServletRequest request = attributes.getRequest();
        final String actionStr = request.getParameter("action");

        if (actionStr == null || actionStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required parameter: action");
        }

        final Action action;
        try {
            action = Action.valueOf(actionStr.toUpperCase());
        } catch (final IllegalArgumentException e) {
            log.warn("Invalid action value provided: {}", actionStr);
            throw new IllegalArgumentException("Invalid action value: " + actionStr, e);
        }

        switch (action) {
            case START:
                log.info("Starting leader election service");
                this.clusterLeaderService.start();
                break;
            case STOP:
                log.info("Stopping leader election service");
                this.clusterLeaderService.stop();
                break;
            case RESTART:
                log.info("Restarting leader election service");
                this.clusterLeaderService.stop();
                this.clusterLeaderService.start();
                break;
            default:
                log.error("Unknown action: {}", action);
                throw new UnsupportedOperationException("Unknown action: " + action.name());
        }
    }

    /**
     * Operations that this actuator can perform on the leader service.
     */
    public enum Action {
        /**
         * Stop the leader election service.
         */
        STOP,
        /**
         * Start the leader election service.
         */
        START,
        /**
         * Stop then start the leader election service.
         */
        RESTART,

        /**
         * NOOP action for the purpose of testing unknown actions.
         */
        TEST,
    }
}
