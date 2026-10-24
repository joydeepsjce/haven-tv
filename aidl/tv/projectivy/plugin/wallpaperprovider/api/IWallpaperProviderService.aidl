// Projectivy Launcher's wallpaper provider plugin interface, API version 1.
// From https://github.com/spocky/projectivy-plugin-wallpaper-provider (Apache License 2.0).
// The package and method order must stay exactly as Projectivy publishes them.
package tv.projectivy.plugin.wallpaperprovider.api;

import tv.projectivy.plugin.wallpaperprovider.api.Event;
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper;

interface IWallpaperProviderService {
    List<Wallpaper> getWallpapers(in Event event);
    String getPreferences();
    void setPreferences(String params);
}
