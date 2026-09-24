/*
 * Copyright (c) 2024, Psiphon Inc.
 * All rights reserved.
 *
 * Licensed under the GNU General Public License version 3 or later.
 * Source: https://github.com/Psiphon-Inc/psiphon-android
 */
package ca.psiphon;

public class Tun2SocksJniLoader {
    static {
        System.loadLibrary("tun2socks");
    }

    public static void initializeLogger(String className, String methodName) {
        String formattedClassName = className.replace('.', '/');
        initTun2socksLogger(formattedClassName, methodName);
    }

    private native static void initTun2socksLogger(String className, String logMethodName);

    public native static void runTun2Socks(
            int vpnInterfaceFileDescriptor,
            int vpnInterfaceMTU,
            String vpnIpv4Address,
            String vpnIpv4NetMask,
            String vpnIpv6Address,
            String socksServerAddress,
            String udpgwServerAddress,
            int udpgwTransparentDNS);

    public native static void terminateTun2Socks();
}
