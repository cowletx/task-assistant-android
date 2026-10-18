// core-schedule：纯 Kotlin/JVM 模块，不含任何 Android 依赖。
// 日程生成与训练流程的业务规则集中在这里，因此可以用普通 JUnit 测试直接验证（无需模拟器）。
//
// 注意：这里只声明一次 Kotlin JVM 插件。之前的写法同时写了
//   id("org.jetbrains.kotlin.jvm") 和 kotlin("jvm")
// 两者是同一个插件，重复应用会让 Gradle 报错/告警，已修正为只保留后者（版本由根项目统一管理）。
plugins {
    kotlin("jvm")
}

kotlin {
    // 用 toolchain 指定 JDK 17：与本项目 app 模块的 jvmTarget 保持一致
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
