(function () {
    "use strict";

    var sections = {
        play:     { title: "Играть",       color: "#744da9" },
        library:  { title: "Библиотека",   color: "#6ca315" },
        mods:     { title: "Моды",          color: "#008f9c" },
        skins:    { title: "Скины",         color: "#b4009e" },
        backups:  { title: "Бэкапы",        color: "#0078d7" },
        settings: { title: "Настройки",     color: "#252525" }
    };

    function companionUri(section) {
        return "msc-launcher://open/" + encodeURIComponent(section);
    }

    function setStatus(message) {
        document.getElementById("status").textContent = message;
    }

    function launchSection(section) {
        var uri = new Windows.Foundation.Uri(companionUri(section));
        Windows.System.Launcher.launchUriAsync(uri).then(function (started) {
            if (!started) {
                setStatus("Companion-компонент MSC Launcher Metro не установлен.");
            }
        }, function () {
            setStatus("Не удалось открыть MSC Launcher Metro.");
        });
    }

    function tileColor(hex) {
        return {
            a: 255,
            r: parseInt(hex.substring(1, 3), 16),
            g: parseInt(hex.substring(3, 5), 16),
            b: parseInt(hex.substring(5, 7), 16)
        };
    }

    function pinSection(section) {
        var info = sections[section];
        var tileId = "msc-" + section;
        if (Windows.UI.StartScreen.SecondaryTile.exists(tileId)) {
            setStatus("Плитка «" + info.title + "» уже закреплена.");
            return;
        }

        var logo = new Windows.Foundation.Uri("ms-appx:///images/logo-150.png");
        var tile = new Windows.UI.StartScreen.SecondaryTile(
            tileId,
            info.title,
            info.title,
            "open:" + section,
            Windows.UI.StartScreen.TileOptions.showNameOnLogo,
            logo
        );
        tile.foregroundText = Windows.UI.StartScreen.ForegroundText.light;
        tile.backgroundColor = tileColor(info.color);
        tile.smallLogo = new Windows.Foundation.Uri("ms-appx:///images/logo-30.png");
        if (tile.visualElements) {
            tile.visualElements.backgroundColor = tileColor(info.color);
            tile.visualElements.square150x150Logo = logo;
            tile.visualElements.wide310x150Logo =
                new Windows.Foundation.Uri("ms-appx:///images/logo-310x150.png");
            tile.visualElements.square70x70Logo =
                new Windows.Foundation.Uri("ms-appx:///images/logo-70.png");
            tile.visualElements.showNameOnSquare150x150Logo = true;
            tile.visualElements.showNameOnWide310x150Logo = true;
        }
        tile.requestCreateAsync().then(function (created) {
            setStatus(created
                ? "Плитка «" + info.title + "» добавлена на начальный экран."
                : "Закрепление плитки отменено.");
        });
    }

    function wireTiles() {
        var tiles = document.querySelectorAll(".tile");
        for (var i = 0; i < tiles.length; i++) {
            (function (tile) {
                var section = tile.getAttribute("data-section");
                tile.querySelector(".open").addEventListener("click", function () {
                    launchSection(section);
                });
                tile.querySelector(".pin").addEventListener("click", function (event) {
                    event.stopPropagation();
                    pinSection(section);
                });
            }(tiles[i]));
        }
    }

    function handleActivation(event) {
        var detail = event && event.detail;
        var args = detail && detail.arguments ? String(detail.arguments) : "";
        if (args.indexOf("open:") === 0) {
            launchSection(args.substring(5));
        }
    }

    document.addEventListener("DOMContentLoaded", wireTiles, false);
    if (window.Windows && Windows.UI && Windows.UI.WebUI) {
        Windows.UI.WebUI.WebUIApplication.addEventListener("activated", handleActivation);
    }
}());
