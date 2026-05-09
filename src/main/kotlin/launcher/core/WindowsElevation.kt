package launcher.core

object WindowsElevation {

    fun ensureAdminOrRelaunch(): Boolean {
        // 由于我们在 C# 外壳层 (MD3L.exe) 已经强制拦截并获取了管理员权限，
        // 这里 Java 层面的提权可以直接跳过，防止发生二次重启循环。
        return true
    }
}
