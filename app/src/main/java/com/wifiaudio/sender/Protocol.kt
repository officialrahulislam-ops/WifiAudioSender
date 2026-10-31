package com.wifiaudio.sender

/** Keep in sync with protocol.py on the Windows side. */
object Protocol {
    const val APP_ID = "wifi-audio"
    const val VERSION = 1
    const val DISCOVERY_PORT = 48010
    const val DEFAULT_TCP_PORT = 48011

    const val T_HELLO = 1
    const val T_OK = 2
    const val T_REJECT = 3
    const val T_AUDIO = 4
    const val T_PING = 5
    const val T_BYE = 6
    const val T_KEEPALIVE = 7

    const val MAX_PAYLOAD = 65535
}