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

object TrayManager {
    private var trayIcon: TrayIcon? = null
    private var mainWindow: java.awt.Window? = null

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

            val scaledWidth = traySize.width.coerceAtMost(32)
            val scaledHeight = traySize.height.coerceAtMost(32)

            val scaledImage = image.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH)
            val trayImage = BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB)
            val g = trayImage.createGraphics()
            g.drawImage(scaledImage, 0, 0, null)
            g.dispose()

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

            trayIcon = TrayIcon(trayImage, "MD3L - Minecraft Launcher", popup)
            trayIcon?.isImageAutoSize = true

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

    fun setMainWindow(window: java.awt.Window) {
        mainWindow = window
    }

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

    fun hideMainWindow() {
        mainWindow?.isVisible = false
    }

    fun remove() {
        trayIcon?.let { icon ->
            SystemTray.getSystemTray().remove(icon)
            trayIcon = null
        }
    }
}
