package com.tinkerpop.gremlin.server;

import com.tinkerpop.gremlin.driver.Client;
import com.tinkerpop.gremlin.driver.Cluster;
import com.tinkerpop.gremlin.driver.Item;
import com.tinkerpop.gremlin.driver.ResultSet;
import com.tinkerpop.gremlin.process.Traversal;
import com.tinkerpop.gremlin.structure.Graph;
import com.tinkerpop.gremlin.util.TimeUtil;
import com.tinkerpop.gremlin.util.function.SFunction;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for gremlin-driver configurations and settings.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class GremlinDriverIntegrateTest extends AbstractGremlinServerIntegrationTest {

    @Rule
    public TestName name = new TestName();

    public static class RemoteTraversal implements SFunction<Graph, Traversal> {
        public Traversal apply(final Graph g) {
            return g.V().out().range(0,9);
        }
    }

    /**
     * Configure specific Gremlin Server settings for specific tests.
     */
    @Override
    public Settings overrideSettings(final Settings settings) {
        final String nameOfTest = name.getMethodName();
        switch (nameOfTest) {
            case "shouldSendTraversal":
                settings.scriptEngines.get("gremlin-groovy").scripts.add("scripts/generate-sample.groovy");
                break;
        }

        return settings;
    }

    @Test
    public void shouldSendTraversal() throws Exception {
        final Cluster cluster = Cluster.open();
        final Client client = cluster.connect();

        final List<Item> results = client.submit(new RemoteTraversal()).all().get();
        assertEquals(10, results.size());
        cluster.close();
    }

    @Test
    public void shouldProcessRequestsOutOfOrder() throws Exception {
        final Cluster cluster = Cluster.open();
        final Client client = cluster.connect();

        final ResultSet rsFive = client.submit("Thread.sleep(5000);'five'");
        final ResultSet rsZero = client.submit("'zero'");

        final CompletableFuture<List<Item>> futureFive = rsFive.all();
        final CompletableFuture<List<Item>> futureZero = rsZero.all();

        final long start = System.nanoTime();
        assertFalse(futureFive.isDone());
        assertEquals("zero", futureZero.get().get(0).getString());

        System.out.println("Eval of 'zero' complete: "  + TimeUtil.millisSince(start));

        assertFalse(futureFive.isDone());
        assertEquals("five", futureFive.get(10, TimeUnit.SECONDS).get(0).getString());

        System.out.println("Eval of 'five' complete: "  + TimeUtil.millisSince(start));
    }

    @Test
    public void shouldWaitForAllResultsToArrive() throws Exception {
        final Cluster cluster = Cluster.open();
        final Client client = cluster.connect();

        final AtomicInteger checked = new AtomicInteger(0);
        final ResultSet results = client.submit("[1,2,3,4,5,6,7,8,9]");
        while (!results.allItemsAvailable()) {
            assertTrue(results.getAvailableItemCount() < 10);
            checked.incrementAndGet();
            Thread.sleep(100);
        }

        assertTrue(checked.get() > 0);
        assertEquals(9, results.getAvailableItemCount());
        cluster.close();
    }

    @Test
    public void shouldStream() throws Exception {
        final Cluster cluster = Cluster.open();
        final Client client = cluster.connect();

        final ResultSet results = client.submit("[1,2,3,4,5,6,7,8,9]");
        final AtomicInteger counter = new AtomicInteger(0);
        results.stream().map(i -> i.get(Integer.class) * 2).forEach(i -> assertEquals(counter.incrementAndGet() * 2, Integer.parseInt(i.toString())));

        cluster.close();
    }

    @Test
    public void shouldCloseWithServerDown() throws Exception {
        final Cluster cluster = Cluster.open();
        cluster.connect();

        stopServer();

        cluster.close();
    }

    @Test
    public void shouldHandleRequestSentThatNeverReturns() throws Exception {
        final Cluster cluster = Cluster.open();
        final Client client = cluster.connect();

        final ResultSet results = client.submit("'should-not-ever-get-back-coz-we-killed-the-server'");

        stopServer();

        assertEquals(0, results.getAvailableItemCount());

        cluster.close();
    }
}
