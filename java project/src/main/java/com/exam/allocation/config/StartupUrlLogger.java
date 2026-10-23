package com.exam.allocation.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** Prints localhost and LAN URLs after the embedded web server has started. */
@Component
public class StartupUrlLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupUrlLogger.class);

    @EventListener
    public void onWebServerStarted(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        List<String> urls = new ArrayList<>();
        urls.add("http://localhost:" + port);
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface network = interfaces.nextElement();
                    if (!network.isUp() || network.isLoopback()) continue;
                    Enumeration<InetAddress> addresses = network.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (address instanceof Inet4Address && address.isSiteLocalAddress()
                                && !address.isLoopbackAddress()) {
                            urls.add("http://" + address.getHostAddress() + ":" + port
                                    + " (" + network.getDisplayName() + ")");
                        }
                    }
                }
            }
        } catch (SocketException | SecurityException ex) {
            log.warn("Could not list local network addresses for the application URL banner: {}",
                    ex.getMessage());
        }

        StringBuilder banner = new StringBuilder()
                .append(System.lineSeparator())
                .append("------------------------------------------------------------")
                .append(System.lineSeparator())
                .append("Room Allocation System is ready:")
                .append(System.lineSeparator());
        for (String url : urls) {
            banner.append("  ").append(url).append(System.lineSeparator());
        }
        banner.append("Allow Java through the firewall for access from other devices.")
                .append(System.lineSeparator())
                .append("------------------------------------------------------------");
        log.info("{}", banner);
    }
}
