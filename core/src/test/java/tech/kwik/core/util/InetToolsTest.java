/*
 * Copyright © 2024, 2025, 2026 Peter Doornbosch
 *
 * This file is part of Kwik, an implementation of the QUIC protocol in Java.
 *
 * Kwik is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Kwik is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package tech.kwik.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.*;

class InetToolsTest {

    private MockedStatic<InetAddress> dns;

    @BeforeEach
    void prepareDns() throws Exception {
        InetAddress ipv4 = InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 1});
        InetAddress ipv6 = InetAddress.getByAddress(new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        dns = mockStatic(InetAddress.class, CALLS_REAL_METHODS);
        dns.when(() -> InetAddress.getAllByName("www.google.com")).thenReturn(new InetAddress[]{ipv4, ipv6});
        dns.when(() -> InetAddress.getAllByName("ipv4only.arpa")).thenReturn(new InetAddress[]{ipv4});
    }

    @AfterEach
    void closeDns() {
        dns.close();
    }

    @Test
    void whenPreferenceIsIP4AndIP4IsAvailableThenIP4IsReturned() throws Exception {
        // When
        InetAddress address = InetTools.lookupAddress("www.google.com", InetTools.IPversionOption.UseIPv4);

        // Then
        assertThat(address).isInstanceOf(Inet4Address.class);
    }

    @Test
    void whenPreferenceIsIP6AndIP6IsAvailableThenIP6IsReturned() throws Exception {
        // When
        InetAddress address = InetTools.lookupAddress("www.google.com", InetTools.IPversionOption.UseIPv6);

        // Then
        assertThat(address).isInstanceOf(Inet6Address.class);
    }

    @Test
    void whenPreferenceIsIP6WithFallbackToIP4AndIP6IsAvailableThenIP6IsReturned() throws Exception {
        // When
        InetAddress address = InetTools.lookupAddress("www.google.com", InetTools.IPversionOption.PreferIPv6);

        // Then
        assertThat(address).isInstanceOf(Inet6Address.class);
    }

    @Test
    void whenPreferenceIsIP6WithFallbackToIP4AndIP6IsNotAvailableThenIP6IsReturned() throws Exception {
        // When
        InetAddress address = InetTools.lookupAddress("ipv4only.arpa", InetTools.IPversionOption.PreferIPv6);

        // Then
        assertThat(address).isInstanceOf(Inet4Address.class);
    }

    @Test
    void whenPreferenceIsIP4WithFallbackToIP6AndIP4IsAvailableThenIP4IsReturned() throws Exception {
        // When
        InetAddress address = InetTools.lookupAddress("www.google.com", InetTools.IPversionOption.PreferIPv4);

        // Then
        assertThat(address).isInstanceOf(Inet4Address.class);
    }
}
