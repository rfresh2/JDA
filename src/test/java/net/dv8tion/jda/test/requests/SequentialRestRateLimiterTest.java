/*
 * Copyright 2015 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dv8tion.jda.test.requests;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.requests.Method;
import net.dv8tion.jda.api.requests.RestConfig;
import net.dv8tion.jda.api.requests.RestRateLimiter;
import net.dv8tion.jda.api.requests.Route;
import net.dv8tion.jda.api.requests.SequentialRestRateLimiter;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SequentialRestRateLimiterTest {
    private static final Route CREATE_MESSAGE = Route.custom(Method.POST, "channels/{channel_id}/messages");

    private ScheduledExecutorService scheduler;
    private RestRateLimiter.RateLimitConfig rateLimitConfig;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        var globalRateLimit = RestRateLimiter.GlobalRateLimit.create();
        globalRateLimit.setClassic(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1));
        rateLimitConfig = new RestRateLimiter.RateLimitConfig(scheduler, globalRateLimit, true);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void testQueueLimitIsDisabledByDefault() {
        var restConfig = new RestConfig();
        var rateLimiter = restConfig.getRateLimiterFactory().apply(rateLimitConfig);
        var requests = enqueue(rateLimiter, CREATE_MESSAGE.compile("1"), 100);

        assertThat(restConfig.getMaxQueuedRequestsPerBucket()).isZero();
        assertThat(requests).noneMatch(TestWork::isCancelled);
    }

    @Test
    void testQueueLimitCancelsOldestBatch() {
        var restConfig = new RestConfig().setMaxQueuedRequestsPerBucket(20);
        var rateLimiter = restConfig.getRateLimiterFactory().apply(rateLimitConfig);
        var requests = enqueue(rateLimiter, CREATE_MESSAGE.compile("1"), 21);

        assertThat(restConfig.getMaxQueuedRequestsPerBucket()).isEqualTo(20);
        assertThat(requests.subList(0, 2)).allMatch(TestWork::isCancelled);
        assertThat(requests.subList(2, requests.size())).noneMatch(TestWork::isCancelled);
    }

    @Test
    void testQueueLimitPreservesPriorityRequests() {
        var rateLimiter = new SequentialRestRateLimiter(rateLimitConfig, 10);
        var route = CREATE_MESSAGE.compile("1");
        var priority = new TestWork(route, true);
        rateLimiter.enqueue(priority);

        var requests = enqueue(rateLimiter, route, 10);

        assertThat(priority.isCancelled()).isFalse();
        assertThat(requests.get(0).isCancelled()).isTrue();
        assertThat(requests.subList(1, requests.size())).noneMatch(TestWork::isCancelled);
    }

    @Test
    void testQueueLimitAppliesPerBucket() {
        var rateLimiter = new SequentialRestRateLimiter(rateLimitConfig, 2);
        var firstBucket = enqueue(rateLimiter, CREATE_MESSAGE.compile("1"), 3);
        var secondBucket = enqueue(rateLimiter, CREATE_MESSAGE.compile("2"), 2);

        assertThat(firstBucket.get(0).isCancelled()).isTrue();
        assertThat(firstBucket.subList(1, firstBucket.size())).noneMatch(TestWork::isCancelled);
        assertThat(secondBucket).noneMatch(TestWork::isCancelled);
    }

    @Test
    void testQueueLimitRejectsNegativeValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RestConfig().setMaxQueuedRequestsPerBucket(-1))
                .withMessageContaining("may not be negative");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SequentialRestRateLimiter(rateLimitConfig, -1))
                .withMessageContaining("may not be negative");
    }

    private static List<TestWork> enqueue(RestRateLimiter rateLimiter, Route.CompiledRoute route, int requestCount) {
        var requests = new ArrayList<TestWork>(requestCount);
        for (var i = 0; i < requestCount; i++) {
            var request = new TestWork(route, false);
            requests.add(request);
            rateLimiter.enqueue(request);
        }
        return requests;
    }

    private static class TestWork implements RestRateLimiter.Work {
        private final Route.CompiledRoute route;
        private final boolean priority;
        private boolean cancelled;

        private TestWork(Route.CompiledRoute route, boolean priority) {
            this.route = route;
            this.priority = priority;
        }

        @Nonnull
        @Override
        public Route.CompiledRoute getRoute() {
            return route;
        }

        @Nonnull
        @Override
        public JDA getJDA() {
            throw new AssertionError("Request should not execute");
        }

        @Nullable
        @Override
        public Response execute() {
            throw new AssertionError("Request should not execute");
        }

        @Override
        public boolean isSkipped() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public boolean isPriority() {
            return priority;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
