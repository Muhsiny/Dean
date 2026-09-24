package com.siper.vpn

import java.io.File
import org.json.JSONObject

object PsiphonFallbackConfig {
    const val SERVER_ENTRIES_ASSET = "psiphon_server_entries.txt"

    private const val PROPAGATION_CHANNEL_ID = "FFFFFFFFFFFFFFFF"
    private const val SPONSOR_ID = "1111111111111111"
    private const val SERVER_ENTRY_SIGNATURE_KEY =
        "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA="
    private const val REMOTE_SERVER_LIST_URL =
        "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed"
    private const val REMOTE_SERVER_LIST_SIGNATURE_KEY =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH" +
        "5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5" +
        "OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42Kcot" +
        "LFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6" +
        "/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7G" +
        "stZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1O" +
        "geF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8" +
        "u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz" +
        "31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xal" +
        "KxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="

    fun render(dataRootDirectory: File, clientVersion: String): String {
        dataRootDirectory.mkdirs()
        return JSONObject().apply {
            put("PropagationChannelId", PROPAGATION_CHANNEL_ID)
            put("SponsorId", SPONSOR_ID)
            put("ClientVersion", clientVersion)
            put("DataRootDirectory", dataRootDirectory.absolutePath)
            put("ServerEntrySignaturePublicKey", SERVER_ENTRY_SIGNATURE_KEY)
            put("RemoteServerListSignaturePublicKey", REMOTE_SERVER_LIST_SIGNATURE_KEY)
            put("RemoteServerListUrl", REMOTE_SERVER_LIST_URL)
            put("LocalSocksProxyPort", 0)
            put("DisableLocalHTTPProxy", true)
            put("EstablishTunnelTimeoutSeconds", 300)
            put("ConnectionWorkerPoolSize", 12)
            put("EmitDiagnosticNotices", true)
            put("EmitDiagnosticNetworkParameters", false)
        }.toString()
    }
}
