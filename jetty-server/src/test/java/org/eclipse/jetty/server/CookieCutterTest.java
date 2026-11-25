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

package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.servlet.http.Cookie;

import org.junit.Test;

public class CookieCutterTest
{
    private static Cookie[] parseCookieHeaders(String... headers)
    {
        CookieCutter cutter = new CookieCutter();
        for (String header : headers)
        {
            cutter.addCookieField(header);
        }
        return cutter.getCookies();
    }

    private static List<String> toNameValues(Cookie[] cookies)
    {
        List<String> nameValues = new ArrayList<>();
        for (Cookie cookie : cookies)
        {
            nameValues.add(cookie.getName() + "=" + cookie.getValue());
        }
        return nameValues;
    }

    private static void assertCookies(String input, String... expected)
    {
        assertThat("Cookies of <" + input + ">", toNameValues(parseCookieHeaders(input)), is(Arrays.asList(expected)));
    }

    private static void assertNotSmuggled(String input)
    {
        Cookie[] cookies = parseCookieHeaders(input);
        for (Cookie cookie : cookies)
        {
            assertThat("Cookie.name of <" + input + ">", cookie.getName(), not(is("DISPLAY_LANGUAGE")));
            assertThat("Cookie.value of <" + input + ">", cookie.getValue(), not(containsString("JSESSIONID")));
            assertThat("Cookie.value of <" + input + ">", cookie.getValue(), not(containsString("1337")));
        }
    }

    /**
     * CVE-2023-26049: a ';' inside a quoted value must not let that cookie absorb the following cookies.
     */
    @Test
    public void testQuotedSemicolonRejected()
    {
        assertCookies("A=\"1; B=2\"; C=3", "C=3");
        assertCookies("A=\"1; B=2; C=3");
        assertCookies("SESSION=1234; other=\"quoted=;HttpOnly_cookie=secret\"", "SESSION=1234");
        assertCookies("name=value; other=\"quoted=;value\"", "name=value");
        assertCookies("\"a;b\"=c; D=4", "D=4");
    }

    /**
     * CVE-2023-26049: the ';' separator escaped by a backslash in the quoted value is still rejected.
     */
    @Test
    public void testEscapedQuotedSemicolonRejected()
    {
        assertCookies("A=\"1\\; B=2\"; C=3", "C=3");
        assertCookies("A=\"1\\; B=2; C=3\"", new String[0]);
    }

    /**
     * CVE-2023-26049 / GHSA-p26g-97m4-6q7c: an attacker controlled cookie with a quoted value
     * must not smuggle the value of a following (possibly HttpOnly) cookie.
     */
    @Test
    public void testCookieSmuggling()
    {
        // The advisory example
        assertNotSmuggled("DISPLAY_LANGUAGE=\"b; JSESSIONID=1337; c=d\"");
        assertNotSmuggled("DISPLAY_LANGUAGE=\"b; JSESSIONID=1337\"");
        // Smuggled cookie last, so the quote is never terminated
        assertNotSmuggled("DISPLAY_LANGUAGE=\"b; JSESSIONID=1337");
        // The ';' separator escaped by a backslash in the attacker controlled value
        assertNotSmuggled("DISPLAY_LANGUAGE=\"b\\; JSESSIONID=1337");
        assertNotSmuggled("DISPLAY_LANGUAGE=\"b\\; JSESSIONID=1337\"");
        assertNotSmuggled("DISPLAY_LANGUAGE=\"b\\; JSESSIONID=1337; c=d\"");
    }

    /**
     * A rejected cookie must not affect the cookies that follow it.
     */
    @Test
    public void testRejectionDoesNotLeak()
    {
        // nameless rejected token
        assertCookies("=\"a;b\"; C=3", "C=3");
        // rejection is per cookie and per header field
        assertCookies("A=\"x;y\"; B=2; C=\"3\"", "B=2", "C=3");
        assertEquals(Arrays.asList("B=2"), toNameValues(parseCookieHeaders("A=\"x;y\"", "B=2")));

        // attributes following a rejected cookie are not applied to an earlier cookie
        Cookie[] cookies = parseCookieHeaders("$Version=1; A=1; $Path=/a; B=\"x;y\"; $Path=/b; C=3; $Path=/c");
        assertEquals(Arrays.asList("A=1", "C=3"), toNameValues(cookies));
        assertEquals("/a", cookies[0].getPath());
        assertEquals("/c", cookies[1].getPath());

        cookies = parseCookieHeaders("A=1; B=\"x;y\"; $Path=/b");
        assertEquals(Arrays.asList("A=1"), toNameValues(cookies));
        assertNull(cookies[0].getPath());
    }

    /**
     * Behaviour unchanged by the fix.
     */
    @Test
    public void testUnchangedCookies()
    {
        assertCookies("A=1; B=2; C=3", "A=1", "B=2", "C=3");
        assertCookies("A=\"1\"; B=2; C=3", "A=1", "B=2", "C=3");
        assertCookies("A=\"1 B=2\"; C=3", "A=1 B=2", "C=3");
        assertCookies("A=\"1,\"; B=2; C=3", "A=1,", "B=2", "C=3");
        assertCookies("A=\"\\1\"; B=2; C=3", "A=1", "B=2", "C=3");
        assertCookies("A=\"a\\\\\"; B=2", "A=a\\", "B=2");
        assertCookies("A=a\\b; B=2", "A=a\\b", "B=2");
        assertCookies("name=quoted=\"\\\"value\\\"\"", "name=quoted=\"value\"");
        assertCookies("__utmz=12345.1.1.1.utmcsr=google|utmccn=(organic)|utmcmd=organic|utmctr=(not%20provided); A=1",
            "__utmz=12345.1.1.1.utmcsr=google|utmccn=(organic)|utmcmd=organic|utmctr=(not%20provided)", "A=1");

        Cookie[] cookies = parseCookieHeaders("$Version=1; A=\"x\"; $Path=/p; $Domain=acme.com");
        assertEquals(Arrays.asList("A=x"), toNameValues(cookies));
        assertEquals(1, cookies[0].getVersion());
        assertEquals("/p", cookies[0].getPath());
        assertEquals("acme.com", cookies[0].getDomain());
    }
}
