package com.simplelabel.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

@Service
public class AdminAccessService {
    private final String configuredAddress;

    public AdminAccessService(@Value("${simplelabel.admin-ip:}") String configuredAddress) {
        this.configuredAddress = configuredAddress == null ? "" : configuredAddress.trim();
    }

    public boolean isAllowed(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return false;
        try {
            InetAddress client = InetAddress.getByName(clientIp);
            if (client.isLoopbackAddress()) return true;
            if (!configuredAddress.isBlank() && client.getHostAddress().equals(
                    InetAddress.getByName(configuredAddress).getHostAddress())) return true;
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    if (client.equals(addresses.nextElement())) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }
}
