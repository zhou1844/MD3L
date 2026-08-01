package launcher.core

object MicrosoftTranslate {
    suspend fun toChinese(text: String): String = text
    suspend fun toEnglish(text: String): String? {
        if (text.isBlank()) return null
        val zhChars = text.count { it.code in 0x4E00..0x9FFF }
        if (zhChars == 0) return text
        val lower = text.trim().lowercase()
        return MINECRAFT_TERMS[lower]
    }

    private val MINECRAFT_TERMS = mapOf(
        "地图" to "map", "皮肤" to "skin", "材质包" to "texture pack",
        "资源包" to "resource pack", "行为包" to "behavior pack",
        "模组" to "mod", "模块" to "mod", "插件" to "plugin",
        "武器" to "weapon", "装备" to "armor", "盔甲" to "armor",
        "工具" to "tools", "建筑" to "building", "科技" to "tech",
        "魔法" to "magic", "冒险" to "adventure", "生存" to "survival",
        "优化" to "optimization", "性能" to "performance",
        "光影" to "shader", "光追" to "ray tracing",
        "生物" to "mob", "怪物" to "monster", "动物" to "animal",
        "农业" to "farming", "食物" to "food", "烹饪" to "cooking",
        "存储" to "storage", "背包" to "backpack", "箱子" to "chest",
        "传送" to "teleport", "领地" to "land claim", "权限" to "permission",
        "经济" to "economy", "商店" to "shop", "交易" to "trade",
        "小游戏" to "minigame", "跑酷" to "parkour", "pvp" to "pvp",
        "家具" to "furniture", "装饰" to "decoration", "家居" to "furniture",
        "机械" to "machine", "自动化" to "automation", "红石" to "redstone",
        "附魔" to "enchant", "药水" to "potion", "酿造" to "brewing",
        "维度" to "dimension", "空岛" to "skyblock", "地牢" to "dungeon",
    )
}
