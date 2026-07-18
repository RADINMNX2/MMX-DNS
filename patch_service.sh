#!/bin/bash
sed -i 's/private var workerThread: Thread? = null//' app/src/main/java/com/example/service/DnsVpnService.kt
sed -i 's/workerThread = thread(start = true, name = "DNS-VPN-Worker") {/serviceScope.launch(Dispatchers.IO + CoroutineName("DNS-VPN-Worker")) {/' app/src/main/java/com/example/service/DnsVpnService.kt
sed -i 's/return@thread/return@launch/' app/src/main/java/com/example/service/DnsVpnService.kt
sed -i 's/workerThread?.interrupt()//' app/src/main/java/com/example/service/DnsVpnService.kt
sed -i 's/workerThread = null//' app/src/main/java/com/example/service/DnsVpnService.kt
