#!/bin/bash
sed -i 's/implementation("org.chromium.net:cronet-api:113.5672.61")//' app/build.gradle.kts
sed -i 's/implementation("org.chromium.net:cronet-common:113.5672.61")//' app/build.gradle.kts
sed -i '/exclude(group = "org.chromium.net", module = "cronet-api")/d' app/build.gradle.kts
sed -i '/exclude(group = "org.chromium.net", module = "cronet-common")/d' app/build.gradle.kts
