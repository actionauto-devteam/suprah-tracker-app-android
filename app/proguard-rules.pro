# OkHttp, Retrofit, Room, Hilt and kotlinx.serialization ship their own R8 rules.

# Server request/response classes are (de)serialized by kotlinx.serialization: keep their generated serializers.
-keep,includedescriptorclasses class com.suprah.drivertracker.data.remote.dto.**$$serializer { *; }
-keepclassmembers class com.suprah.drivertracker.data.remote.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.suprah.drivertracker.data.remote.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
