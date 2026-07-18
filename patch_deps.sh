#!/bin/bash
sed -i 's/implementation("org.chromium.net:cronet-api:113.5672.61")//' app/build.gradle.kts
sed -i 's/implementation("org.chromium.net:cronet-common:113.5672.61")//' app/build.gradle.kts
sed -i 's/implementation(libs.play.services.cronet)//' app/build.gradle.kts
sed -i 's/implementation(libs.cronet.fallback)//' app/build.gradle.kts
sed -i '/implementation(libs.firebase.appcheck.recaptcha)/a \  implementation("org.chromium.net:cronet-embedded:113.5672.61")' app/build.gradle.kts
