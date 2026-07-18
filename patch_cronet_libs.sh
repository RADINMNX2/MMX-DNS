#!/bin/bash
sed -i '/implementation("org.chromium.net:cronet-embedded:113.5672.61")/d' app/build.gradle.kts
sed -i '/exclude(group = "org.chromium.net", module = "cronet-api")/d' app/build.gradle.kts
sed -i '/exclude(group = "org.chromium.net", module = "cronet-common")/d' app/build.gradle.kts
sed -i '/implementation("org.chromium.net:cronet-api:113.5672.61@jar")/d' app/build.gradle.kts
sed -i '/implementation("org.chromium.net:cronet-common:113.5672.61@jar")/d' app/build.gradle.kts

sed -i '/implementation(libs.firebase.appcheck.recaptcha)/a \  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))' app/build.gradle.kts

