//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.handler;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ThreadLimitHandlerTest
{
    private Server _server;
    private NetworkConnector _connector;
    private LocalConnector _local;


    @Before
    public void before()
        throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _local = new LocalConnector(_server);
        _server.setConnectors(new Connector[] { _local,_connector });

    }

    @After
    public void after()
        throws Exception
    {
        _server.stop();
    }
    
    
    @Test
    public void testNoForwardHeaders() throws Exception
    {
        AtomicReference<String> last = new AtomicReference<>();
        ThreadLimitHandler handler = new ThreadLimitHandler(null,false)
        {
            @Override
            protected int getThreadLimit(String ip)
            {
                last.set(ip);
                return super.getThreadLimit(ip);
            }
        };
        handler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(HttpStatus.OK_200);
            }
        });
        ContextHandler contextHandler = new ContextHandler("/");
        contextHandler.setHandler(handler);
        _server.setHandler(contextHandler);
        _server.start();

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\n\r\n");
        Assert.assertThat(last.get(),Matchers.is("0.0.0.0"));
        
        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.2.3.4\r\n\r\n");
        Assert.assertThat(last.get(),Matchers.is("0.0.0.0"));
        
        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n");
        Assert.assertThat(last.get(),Matchers.is("0.0.0.0"));

        awaitNoRemotes(handler);
    }
    
    @Test
    public void testXForwardForHeaders() throws Exception
    {
        AtomicReference<String> last = new AtomicReference<>();
        ThreadLimitHandler handler = new ThreadLimitHandler("X-Forwarded-For")
        {
            @Override
            protected int getThreadLimit(String ip)
            {
                last.set(ip);
                return super.getThreadLimit(ip);
            }
        };
        ContextHandler contextHandler = new ContextHandler("/");
        contextHandler.setHandler(handler);
        _server.setHandler(contextHandler);
        _server.start();

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\n\r\n");
        Assert.assertThat(last.get(),Matchers.is("0.0.0.0"));
        
        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.2.3.4\r\n\r\n");
        Assert.assertThat(last.get(),Matchers.is("1.2.3.4"));
        
        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n");
        Assert.assertThat(last.get(),Matchers.is("0.0.0.0"));
        
        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.1.1.1\r\nX-Forwarded-For: 6.6.6.6,1.2.3.4\r\nForwarded: for=1.2.3.4\r\n\r\n");
        Assert.assertThat(last.get(),Matchers.is("1.2.3.4"));

        awaitNoRemotes(handler);
    }

    @Test
    public void testForwardHeaders() throws Exception
    {
        AtomicReference<String> last = new AtomicReference<>();
        ThreadLimitHandler handler = new ThreadLimitHandler("Forwarded")
        {
            @Override
            protected int getThreadLimit(String ip)
            {
                last.set(ip);
                return super.getThreadLimit(ip);
            }
        };
        ContextHandler contextHandler = new ContextHandler("/");
        contextHandler.setHandler(handler);
        _server.setHandler(contextHandler);
        _server.start();

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\n\r\n");
        Assert.assertThat(last.get(),Matchers.is("0.0.0.0"));
        
        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.2.3.4\r\n\r\n");
        Assert.assertThat(last.get(),Matchers.is("0.0.0.0"));
        
        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n");
        Assert.assertThat(last.get(),Matchers.is("1.2.3.4"));
        
        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.1.1.1\r\nForwarded: for=6.6.6.6; for=1.2.3.4\r\nX-Forwarded-For: 6.6.6.6\r\nForwarded: proto=https\r\n\r\n");
        Assert.assertThat(last.get(),Matchers.is("1.2.3.4"));

        awaitNoRemotes(handler);
    }
    

    
    @Test
    public void testLimit() throws Exception
    {
        ThreadLimitHandler handler = new ThreadLimitHandler("Forwarded");
        
        handler.setThreadLimit(4);

        AtomicInteger count = new AtomicInteger(0);
        AtomicInteger total = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        handler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(HttpStatus.OK_200);
                if ("/other".equals(target))
                    return;
                
                try
                {
                    count.incrementAndGet();
                    total.incrementAndGet();
                    latch.await();
                }
                catch (InterruptedException e)
                {
                    throw new ServletException(e);
                }
                finally
                {
                    count.decrementAndGet();
                }
                
            }
        });
        ContextHandler contextHandler = new ContextHandler("/");
        contextHandler.setHandler(handler);
        _server.setHandler(contextHandler);
        _server.start();

        Socket[] client = new Socket[10];
        for (int i=0;i<client.length;i++)
        {
            client[i]=new Socket("127.0.0.1",_connector.getLocalPort());
            client[i].getOutputStream().write(("GET /"+i+" HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n").getBytes());  
            client[i].getOutputStream().flush();  
        }
        
        long wait = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while(count.get()<4 && System.nanoTime()<wait) 
            Thread.sleep(1);
        assertThat(count.get(),is(4));
        
        // check that other requests are not blocked
        assertThat(_local.getResponse("GET /other HTTP/1.0\r\nForwarded: for=6.6.6.6\r\n\r\n"),Matchers.containsString(" 200 OK"));
        
        // let the other requests go
        latch.countDown();

        while(total.get()<10 && System.nanoTime()<wait) 
            Thread.sleep(10);
        assertThat(total.get(),is(10));

        while(count.get()>0 && System.nanoTime()<wait) 
            Thread.sleep(10);
        assertThat(count.get(),is(0));

        awaitNoRemotes(handler);
    }

    @Test
    public void testRemotesDoNotAccumulate() throws Exception
    {
        ThreadLimitHandler handler = new ThreadLimitHandler("X-Forwarded-For");
        handler.setHandler(new OkHandler());
        ContextHandler contextHandler = new ContextHandler("/");
        contextHandler.setHandler(handler);
        _server.setHandler(contextHandler);
        _server.start();

        int listeners = contextHandler.getEventListeners().length;

        // Each request comes from a distinct (spoofed) remote address.
        for (int i=0;i<1000;i++)
        {
            String ip = "10.0."+(i/250)+"."+(i%250+1);
            String response = _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: "+ip+"\r\n\r\n");
            assertThat(response,Matchers.containsString(" 200 OK"));
        }

        // No Remote instance must be retained once the requests have completed.
        awaitNoRemotes(handler);

        // The handler must not register a listener per request on the context.
        assertThat(contextHandler.getEventListeners().length,is(listeners));
    }

    @Test
    public void testRemotesReleasedWithoutContext() throws Exception
    {
        ThreadLimitHandler handler = new ThreadLimitHandler("Forwarded");
        handler.setHandler(new OkHandler());
        _server.setHandler(handler);
        _server.start();

        for (int i=0;i<100;i++)
        {
            String response = _local.getResponse("GET / HTTP/1.0\r\nForwarded: for=10.1.0."+(i+1)+"\r\n\r\n");
            assertThat(response,Matchers.containsString(" 200 OK"));
        }

        awaitNoRemotes(handler);
    }

    @Test
    public void testRemoteReleasedWhenDisabledWhileQueued() throws Exception
    {
        AtomicInteger lookups = new AtomicInteger();
        ThreadLimitHandler handler = new ThreadLimitHandler("Forwarded")
        {
            @Override
            protected int getThreadLimit(String ip)
            {
                lookups.incrementAndGet();
                return super.getThreadLimit(ip);
            }
        };
        handler.setThreadLimit(1);

        AtomicInteger count = new AtomicInteger(0);
        AtomicInteger total = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        handler.setHandler(new BlockingHandler(count,total,latch));
        ContextHandler contextHandler = new ContextHandler("/");
        contextHandler.setHandler(handler);
        _server.setHandler(contextHandler);
        _server.start();

        try (Socket client0 = new Socket("127.0.0.1",_connector.getLocalPort());
             Socket client1 = new Socket("127.0.0.1",_connector.getLocalPort()))
        {
            // The first request takes the only permit and blocks.
            client0.getOutputStream().write("GET /0 HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            client0.getOutputStream().flush();
            long wait = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while(count.get()<1 && System.nanoTime()<wait)
                Thread.sleep(1);
            assertThat(count.get(),is(1));

            // The second request is suspended waiting for a permit.
            client1.getOutputStream().write("GET /1 HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            client1.getOutputStream().flush();
            while(lookups.get()<2 && System.nanoTime()<wait)
                Thread.sleep(1);
            assertThat(lookups.get(),is(2));
            Thread.sleep(100);
            assertThat(count.get(),is(1));
            assertThat(handler.getRemoteCount(),is(1));

            // Disable the handler while the second request is queued, then let the requests go.
            handler.setEnabled(false);
            latch.countDown();

            assertThat(readResponse(client0),Matchers.containsString(" 200 OK"));
            assertThat(readResponse(client1),Matchers.containsString(" 200 OK"));
            assertThat(total.get(),is(2));
        }

        awaitNoRemotes(handler);
    }

    private static String readResponse(Socket client) throws IOException
    {
        client.setSoTimeout(10000);
        try (InputStream in = client.getInputStream())
        {
            return IO.toString(in,StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Wait for the handler to have released every {@code Remote} instance it created, so that
     * repeated requests cannot grow the internal map without bound.
     *
     * @param handler the handler to poll
     * @throws InterruptedException if interrupted while waiting
     */
    private void awaitNoRemotes(ThreadLimitHandler handler) throws InterruptedException
    {
        long wait = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while(handler.getRemoteCount()!=0 && System.nanoTime()<wait)
            Thread.sleep(100);
        assertThat(handler.getRemoteCount(),is(0));
    }

    private static class OkHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(HttpStatus.OK_200);
        }
    }

    private static class BlockingHandler extends AbstractHandler
    {
        private final AtomicInteger _count;
        private final AtomicInteger _total;
        private final CountDownLatch _latch;

        private BlockingHandler(AtomicInteger count, AtomicInteger total, CountDownLatch latch)
        {
            _count = count;
            _total = total;
            _latch = latch;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(HttpStatus.OK_200);
            try
            {
                _count.incrementAndGet();
                _total.incrementAndGet();
                _latch.await();
            }
            catch (InterruptedException e)
            {
                throw new ServletException(e);
            }
            finally
            {
                _count.decrementAndGet();
            }
        }
    }
}
