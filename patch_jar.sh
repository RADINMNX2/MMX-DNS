#!/bin/bash
sed -i 's/implementation("org.chromium.net:cronet-embedded:119.6045.31")//' app/build.gradle.kts
sed -i '/implementation(libs.firebase.appcheck.recaptcha)/a \  implementation("org.chromium.net:cronet-api:113.5672.61@jar")\n  implementation("org.chromium.net:cronet-common:113.5672.61@jar")\n  implementation("org.chromium.net:cronet-embedded:113.5672.61@aar")' app/build.gradle.kts
