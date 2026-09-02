import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.properties.Properties
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.detekt)
}

val keystorePropertiesFile: File = sequenceOf(
    File(System.getProperty("user.home"), "AppData/Local/phonly-phone-signing/keystore.properties"),
    rootProject.file("keystore.properties"),
).firstOrNull { it.exists() } ?: rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun hasSigningVars(): Boolean {
    return providers.environmentVariable("SIGNING_KEY_ALIAS").orNull != null
            && providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull != null
            && providers.environmentVariable("SIGNING_STORE_FILE").orNull != null
            && providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull != null
}

fun loadNotificationProperties(): Properties {
    val properties = Properties()
    sequenceOf(
        File(System.getProperty("user.home"), "AppData/Local/phonly-phone-signing/notification.properties"),
        rootProject.file("notification.properties"),
        rootProject.file("local.properties"),
    ).filter { it.exists() }.forEach { file ->
        FileInputStream(file).use { properties.load(it) }
    }
    return properties
}

fun notificationApiUrl(): String {
    val env = sequenceOf(
        System.getenv("NOTIFICATION_API_BASE"),
        System.getenv("NOTIFICATION_API_URL"),
    ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    if (env.isNotEmpty()) return env
    val fromFile = loadNotificationProperties()
    val fileUrl = sequenceOf(
        fromFile.getProperty("notification.apiUrl"),
        fromFile.getProperty("NOTIFICATION_API_BASE"),
        fromFile.getProperty("NOTIFICATION_API_URL"),
    ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    return fileUrl.ifEmpty { "https://phonlyv1.onrender.com" }
}

fun notificationToken(): String {
    val env = sequenceOf(
        System.getenv("NOTIFICATION_SECRET"),
        System.getenv("DEVICE_NOTIFICATION_LAB_TOKEN"),
    ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    if (env.isNotEmpty()) return env
    val fromFile = loadNotificationProperties()
    return sequenceOf(
        fromFile.getProperty("NOTIFICATION_SECRET"),
        fromFile.getProperty("token"),
        fromFile.getProperty("notification.token"),
        fromFile.getProperty("DEVICE_NOTIFICATION_LAB_TOKEN"),
    ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
}

fun deviceImeiOverride(): String {
    val env = System.getenv("DEVICE_IMEI")?.trim().orEmpty()
    if (env.isNotEmpty()) return env
    val fromFile = loadNotificationProperties()
    return sequenceOf(
        fromFile.getProperty("imei"),
        fromFile.getProperty("DEVICE_IMEI"),
    ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
}

fun escapeBuildConfigString(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
}

val debugKeystoreFile = File(System.getProperty("user.home"), ".android/debug.keystore")

base {
    val versionCode = project.property("VERSION_CODE").toString().toInt()
    archivesName = "phone-$versionCode"
}

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = project.property("APP_ID").toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        versionName = project.property("VERSION_NAME").toString()
        versionCode = project.property("VERSION_CODE").toString().toInt()
        buildConfigField(
            "String",
            "NOTIFICATION_API_URL",
            "\"${escapeBuildConfigString(notificationApiUrl())}\""
        )
        buildConfigField(
            "String",
            "NOTIFICATION_TOKEN",
            "\"${escapeBuildConfigString(notificationToken())}\""
        )
        buildConfigField(
            "String",
            "DEVICE_IMEI_OVERRIDE",
            "\"${escapeBuildConfigString(deviceImeiOverride())}\""
        )
    }

    signingConfigs {
        if (debugKeystoreFile.exists()) {
            register("phonlyDebug") {
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                storeFile = debugKeystoreFile
                storePassword = "android"
            }
        }
        if (keystorePropertiesFile.exists()) {
            register("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        } else if (hasSigningVars()) {
            register("release") {
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
                storeFile = file(providers.environmentVariable("SIGNING_STORE_FILE").get())
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            }
        } else {
            logger.warn("Warning: No signing config found. Build will be unsigned.")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // Same debug.keystore as Messages so signature permission works.
            // Keep applicationId = co.phonly.phone (no .debug suffix).
            if (debugKeystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("phonlyDebug")
            }
        }
        release {
            // First Esper cut: do not minify. ProGuard can break InCall / screening.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists() || hasSigningVars()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Do not bake AAAAY IMEI into a signed library APK.
            buildConfigField("String", "DEVICE_IMEI_OVERRIDE", "\"\"")
        }
    }

    flavorDimensions.add("variants")
    productFlavors {
        register("core")
        register("foss")
        register("gplay")
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
    }

    compileOptions {
        val currentJavaVersionFromLibs =
            JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    // Install id is APP_ID (co.phonly.phone). Kotlin/R namespace stays Fossify.
    namespace = "org.fossify.phone"

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = rootProject.file("lint.xml")
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

detekt {
    baseline = file("detekt-baseline.xml")
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

dependencies {
    implementation(libs.fossify.commons)
    implementation(libs.indicator.fast.scroll)
    implementation(libs.autofit.text.view)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.eventbus)
    implementation(libs.libphonenumber)
    implementation(libs.geocoder)
    detektPlugins(libs.compose.detekt)
}

androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(
            PhonlyFossifyClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) {}
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
        )
    }
}

/**
 * Fossify Commons treats any applicationId other than org.fossify.* as a
 * "fake" app. Rewrite those string checks so co.phonly.phone is accepted.
 */
abstract class PhonlyFossifyClassVisitorFactory :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        return object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, mv) {
                    override fun visitLdcInsn(value: Any?) {
                        val mapped = when (value) {
                            "org.fossify." -> "co.phonly."
                            "yfissof" -> "ylnohp."
                            else -> value
                        }
                        super.visitLdcInsn(mapped)
                    }
                }
            }
        }
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        return classData.className == "org.fossify.commons.activities.BaseSimpleActivity"
    }
}
