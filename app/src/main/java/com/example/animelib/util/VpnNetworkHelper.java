package com.example.animelib.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.animelib.AnimeLibApplication;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import javax.net.SocketFactory;

import okhttp3.Dns;

/**
 * Утилита для определения активного VPN-соединения и маршрутизации трафика Kodik
 * напрямую через физическую сеть (Wi-Fi / мобильная сеть) в обход VPN, если ОС это позволяет.
 */
public final class VpnNetworkHelper {

    private static final String TAG = "VpnNetworkHelper";

    private static final List<String> VPN_INTERFACE_PREFIXES = Arrays.asList(
            "tun", "ppp", "wg", "tap", "utun", "ipsec", "pptp", "l2tp"
    );

    private VpnNetworkHelper() {
    }

    @Nullable
    private static Context resolveContext(@Nullable Context context) {
        if (context != null) {
            return context.getApplicationContext() != null ? context.getApplicationContext() : context;
        }
        AnimeLibApplication app = AnimeLibApplication.getInstance();
        return app != null ? app.getApplicationContext() : null;
    }

    /**
     * Проверяет, включен ли сейчас VPN на устройстве.
     */
    public static boolean isVpnActive(@Nullable Context context) {
        Context ctx = resolveContext(context);
        if (ctx != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network activeNetwork = cm.getActiveNetwork();
                    if (activeNetwork != null) {
                        NetworkCapabilities activeCaps = cm.getNetworkCapabilities(activeNetwork);
                        if (activeCaps != null && (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                                || !activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))) {
                            return true;
                        }
                    }
                    Network[] networks = cm.getAllNetworks();
                    if (networks != null) {
                        for (Network network : networks) {
                            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                return true;
                            }
                        }
                    }
                }
            } catch ( Throwable t) {
                Log.w(TAG, "Error checking VPN via ConnectivityManager: " + t.getMessage());
            }
        }

        // Резервная проверка через системные сетевые интерфейсы (tun0, wg0, ppp0 и т.д.)
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface netIf : Collections.list(interfaces)) {
                    if (netIf == null || !netIf.isUp() || netIf.isLoopback()) {
                        continue;
                    }
                    String name = netIf.getName();
                    if (name == null) continue;
                    String lower = name.toLowerCase(Locale.ROOT);
                    for (String prefix : VPN_INTERFACE_PREFIXES) {
                        if (lower.startsWith(prefix)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    /**
     * Находит активную физическую сеть (Wi-Fi / Ethernet / Cellular) с доступом в интернет без VPN.
     */
    @Nullable
    public static Network findPhysicalNonVpnNetwork(@Nullable Context context) {
        Context ctx = resolveContext(context);
        if (ctx == null) {
            return null;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return null;
            }
            Network[] networks = cm.getAllNetworks();
            if (networks == null || networks.length == 0) {
                return null;
            }

            Network bestValidatedWifiOrEth = null;
            Network bestValidatedCellular = null;
            Network fallbackNonVpn = null;

            for (Network network : networks) {
                if (network == null) continue;
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null) continue;

                boolean isNotVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                boolean hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                if (!isNotVpn || !hasInternet) {
                    continue;
                }

                boolean isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                boolean isWifiOrEth = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                boolean isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);

                if (isValidated && isWifiOrEth) {
                    bestValidatedWifiOrEth = network;
                    break;
                } else if (isValidated && isCellular && bestValidatedCellular == null) {
                    bestValidatedCellular = network;
                } else if ((isWifiOrEth || isCellular) && fallbackNonVpn == null) {
                    fallbackNonVpn = network;
                }
            }

            if (bestValidatedWifiOrEth != null) return bestValidatedWifiOrEth;
            if (bestValidatedCellular != null) return bestValidatedCellular;
            return fallbackNonVpn;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to find physical non-VPN network: " + t.getMessage());
            return null;
        }
    }

    /**
     * Создаёт SocketFactory, который при активном VPN пытается привязать сокеты к физической сети
     * (Wi-Fi / мобильная сеть) в обход VPN-туннеля, а при запрете со стороны ОС прозрачно
     * откатывается к стандартному SocketFactory.
     */
    public static SocketFactory createPhysicalNetworkSocketFactory() {
        return new PhysicalNetworkFallbackSocketFactory();
    }

    /**
     * Создаёт DNS-резолвер, который сначала пытается разрешить домен через физическую сеть (мимо VPN DNS),
     * а при неудаче использует резервный резолвер (FastDns с поддержкой DoH).
     */
    public static Dns createPhysicalNetworkDns(Dns fallbackDns) {
        return hostname -> {
            if (hostname == null || hostname.isEmpty()) {
                throw new UnknownHostException("hostname is empty");
            }
            Network physicalNetwork = findPhysicalNonVpnNetwork(null);
            if (physicalNetwork != null) {
                try {
                    InetAddress[] resolved = physicalNetwork.getAllByName(hostname);
                    if (resolved != null && resolved.length > 0) {
                        return Arrays.asList(resolved);
                    }
                } catch (Throwable ignored) {
                }
            }
            return fallbackDns != null ? fallbackDns.lookup(hostname) : Dns.SYSTEM.lookup(hostname);
        };
    }

    private static final class PhysicalNetworkFallbackSocketFactory extends SocketFactory {
        private final SocketFactory defaultFactory = SocketFactory.getDefault();

        private SocketFactory resolveDelegate() {
            Network physical = findPhysicalNonVpnNetwork(null);
            if (physical != null) {
                try {
                    SocketFactory netFactory = physical.getSocketFactory();
                    if (netFactory != null) {
                        return netFactory;
                    }
                } catch (Throwable ignored) {
                }
            }
            return defaultFactory;
        }

        private Socket bindOrFallbackSocket() throws IOException {
            Network physical = findPhysicalNonVpnNetwork(null);
            if (physical != null) {
                try {
                    Socket s = physical.getSocketFactory().createSocket();
                    return s;
                } catch (Throwable ignored) {
                }
            }
            return defaultFactory.createSocket();
        }

        @Override
        public Socket createSocket() throws IOException {
            return bindOrFallbackSocket();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            try {
                return resolveDelegate().createSocket(host, port);
            } catch (Throwable t) {
                return defaultFactory.createSocket(host, port);
            }
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            try {
                return resolveDelegate().createSocket(host, port, localHost, localPort);
            } catch (Throwable t) {
                return defaultFactory.createSocket(host, port, localHost, localPort);
            }
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            try {
                return resolveDelegate().createSocket(host, port);
            } catch (Throwable t) {
                return defaultFactory.createSocket(host, port);
            }
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            try {
                return resolveDelegate().createSocket(address, port, localAddress, localPort);
            } catch (Throwable t) {
                return defaultFactory.createSocket(address, port, localAddress, localPort);
            }
        }
    }
}
