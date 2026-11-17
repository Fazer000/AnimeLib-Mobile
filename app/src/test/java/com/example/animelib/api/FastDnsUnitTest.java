package com.example.animelib.api;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class FastDnsUnitTest {

    @Test
    public void testOrdersIpv4BeforeIpv6AndCachesWithinTtl() throws Exception {
        InetAddress ipv6 = InetAddress.getByName("2606:4700:4700::1111");
        InetAddress ipv4 = InetAddress.getByName("1.1.1.1");
        AtomicInteger resolverCalls = new AtomicInteger(0);

        FastDns dns = new FastDns(host -> {
            resolverCalls.incrementAndGet();
            return Arrays.asList(ipv6, ipv4);
        });

        List<InetAddress> first = dns.lookup("cdn.example.org");
        assertEquals(2, first.size());
        assertEquals(ipv4, first.get(0));
        assertEquals(ipv6, first.get(1));
        assertEquals(1, resolverCalls.get());

        List<InetAddress> second = dns.lookup("cdn.example.org");
        assertEquals(first, second);
        assertEquals("Second lookup within TTL should hit in-memory cache", 1, resolverCalls.get());
    }

    @Test
    public void testParsesDohJsonResponseAddresses() {
        String dohJson = "{\"Status\":0,\"Answer\":["
                + "{\"name\":\"kodikplayer.com\",\"type\":5,\"TTL\":300,\"data\":\"alias.kodikplayer.com.\"},"
                + "{\"name\":\"alias.kodikplayer.com\",\"type\":1,\"TTL\":60,\"data\":\"185.22.153.10\"},"
                + "{\"name\":\"alias.kodikplayer.com\",\"type\":1,\"TTL\":60,\"data\":\"185.22.153.11\"}"
                + "]}";
        List<InetAddress> parsed = FastDns.parseDohJsonAddresses("kodikplayer.com", dohJson);
        assertEquals(2, parsed.size());
        assertEquals("185.22.153.10", parsed.get(0).getHostAddress());
        assertEquals("185.22.153.11", parsed.get(1).getHostAddress());
    }
}
