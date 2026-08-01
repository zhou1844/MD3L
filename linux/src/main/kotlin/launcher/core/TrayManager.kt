package launcher.core

import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.PopupMenu
import java.awt.MenuItem
import java.awt.Font
import java.awt.event.ActionListener
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * 管理系统托盘的类
 * 负责创建和管理系统托盘图标
 */
object TrayManager {
    private var trayIcon: TrayIcon? = null
    private var mainWindow: java.awt.Window? = null
    
    /**
     * 初始化系统托盘
     * 应在主函数中调用，在创建窗口之前
     */
    fun init() {
        if (!SystemTray.isSupported()) {
            println("[Tray] 系统托盘不支持")
            return
        }
        
        try {
            val iconUrl = Thread.currentThread().contextClassLoader.getResource("app_icon.png")
                ?: run {
                    println("[Tray] 找不到图标资源")
                    return
                }
            
            val image = javax.imageio.ImageIO.read(iconUrl) ?: return
            
            val tray = SystemTray.getSystemTray()
            val traySize = tray.trayIconSize
            
            // 缩放图标到适合托盘的大小
            val scaledWidth = traySize.width.coerceAtMost(32)
            val scaledHeight = traySize.height.coerceAtMost(32)
            
            val scaledImage = image.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH)
            val trayImage = BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB)
            val g = trayImage.createGraphics()
            g.drawImage(scaledImage, 0, 0, null)
            g.dispose()
            
            // 创建弹出菜单
            val popup = PopupMenu()
            
            val openItem = MenuItem("打开 MD3L")
            openItem.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            openItem.addActionListener {
                showMainWindow()
            }
            
            val exitItem = MenuItem("退出 MD3L")
            exitItem.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            exitItem.addActionListener {
                System.exit(0)
            }
            
            popup.add(openItem)
            popup.addSeparator()
            popup.add(exitItem)
            
            // 创建托盘图标
            trayIcon = TrayIcon(trayImage, "MD3L - Minecraft Launcher", popup)
            trayIcon?.isImageAutoSize = true
            
            // 双击打开窗口
            trayIcon?.addActionListener {
                showMainWindow()
            }
            
            tray.add(trayIcon)
            println("[Tray] 系统托盘已启用")
            
        } catch (e: Exception) {
            println("[Tray] 初始化失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 设置主窗口引用
     * 应在窗口创建后调用
     */
    fun setMainWindow(window: java.awt.Window) {
        mainWindow = window
    }
    
    /**
     * 显示主窗口
     */
    private fun showMainWindow() {
        SwingUtilities.invokeLater {
            mainWindow?.let { window ->
                window.isVisible = true
                window.toFront()
                if (window is java.awt.Frame) {
                    window.state = java.awt.Frame.NORMAL
                }
            }
        }
    }
    
    /**
     * 隐藏主窗口（最小化到托盘）
     */
    fun hideMainWindow() {
        mainWindow?.isVisible = false
    }
    
    /**
     * 移除托盘图标
     * 应在退出应用时调用
     */
    fun remove() {
        trayIcon?.let { icon ->
            SystemTray.getSystemTray().remove(icon)
            trayIcon = null
        }
    }
}
