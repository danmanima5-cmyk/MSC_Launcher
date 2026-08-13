# 1. Основные настройки сборки
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Оптимизация (можно отключить, если возникнут проблемы со стабильностью)
-dontoptimize

# Release JAR is already Java 8 bytecode.  When a JDK matching the target
# runtime is unavailable, omit ProGuard's second bytecode verification pass;
# name obfuscation does not alter control flow.
-dontpreverify
-dontshrink

# 2. Указываем Java, где искать стандартные библиотеки (актуально для Java 9 и выше)
# Если сборка ругается на отсутствие классов, ProGuard сам подскажет добавить -dontwarn
-dontwarn **

# 3. ГЛАВНОЕ ПРАВИЛО: Сохраняем точку входа приложения
# Мы запрещаем переименовывать класс Main и метод main, чтобы JAR запускался
-keep public class Main {
    public static void main(java.lang.String[]);
}

# Старые версии обновлятора (включая 2.3) проверяют наличие этого точного имени
# внутри скачанного JAR. Переименование класса делало самообновление невозможным.
-keep class GitHubUpdater { *; }
-keep class GitHubUpdater$* { *; }
-keep class JavaFxUpdaterDialogs { *; }
-keep class JavaFxUpdaterDialogs$* { *; }

# Metro JAR объявляет отдельную точку входа в manifest.
-keep public class MetroMain {
    public static void main(java.lang.String[]);
}

# 4. Защита ресурсов и библиотек для работы с Minecraft
# Сохраняем имена классов для корректной работы сериализации JSON (пакеты org.json или com.google.gson)
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# Если вы используете встроенную библиотеку Gson, раскомментируйте строку ниже:
# -keep class com.google.gson.** { *; }

# 5. Сохраняем методы, которые могут вызываться динамически (Reflection)
# Часто лаунчеры вызывают методы модов или Minecraft по текстовому имени.
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Оставляем без изменений имена полей в перечислениях (Enum)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
