#!/bin/bash
sed -i '/implementation("org.chromium.net:cronet-api:113.5672.61@jar")/d' app/build.gradle.kts
sed -i '/implementation("org.chromium.net:cronet-common:113.5672.61@jar")/d' app/build.gradle.kts
sed -i '/implementation("org.chromium.net:cronet-embedded:113.5672.61@aar")/d' app/build.gradle.kts

sed -i '/implementation(libs.firebase.appcheck.recaptcha)/a \  implementation("org.chromium.net:cronet-embedded:113.5672.61") {\n    exclude(group = "org.chromium.net", module = "cronet-api")\n    exclude(group = "org.chromium.net", module = "cronet-common")\n  }\n  implementation("org.chromium.net:cronet-api:113.5672.61@jar")\n  implementation("org.chromium.net:cronet-common:113.5672.61@jar")' app/build.gradle.kts
