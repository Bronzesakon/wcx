pluginManagement {
    repositories {
        maven { url = uri("file:///root/maven-mirror") }
                maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("file:///root/maven-mirror") }
        // jitpack 必须排在通用镜像之前: 腾讯镜像会返回 com.github.* 的模块元数据, 但产物
        // jar 对 GitHub Actions runner 不可达 (2026-10-13 实测 sync-upstream 连续两次
        // "Could not find rhino-45f29f6.jar, searched in mirrors.cloud.tencent.com",
        // 同一 URL 本地代理访问却正常)。元数据一旦从镜像拿到, Gradle 就不会再回退
        // jitpack, 构建直接失败。镜像侧再加 excludeGroup 双保险。
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.Ujhhgtg")
                includeGroup("com.github.Ujhhgtg.rhino")
                includeGroup("com.github.topjohnwu.libsu")
            }
        }
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") {
            content {
                excludeGroup("com.github.Ujhhgtg")
                excludeGroup("com.github.Ujhhgtg.rhino")
                excludeGroup("com.github.topjohnwu.libsu")
            }
        }
        maven("https://api.xposed.info/") {
            content {
                includeGroup("de.robv.android.xposed")
            }
        }
        mavenCentral()
    }

    versionCatalogs {
        create("libs")
    }
}

rootProject.name = "wekit"

include(
    ":app",
    ":libs:common:annotation-scanner",
    ":libs:common:stubs",
    ":libs:common:bsh",
    ":libs:common:reflekt"
)
