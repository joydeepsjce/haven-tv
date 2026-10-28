# Third-party content

The MIT licence covers Haven's code only. The following are included under their own licences.

## Wallpapers

Haven ships no photos. Bing's photo of the day and Wikimedia's Picture of the Day are fetched from
the internet while shown, and each photo's own credit and licence are displayed on screen.

## Font (`assets/fonts/`)

[Inter](https://rsms.me/inter/) by Rasmus Andersson, under the SIL Open Font License 1.1
([text](https://openfontlicense.org/open-font-license-official-text/)).

## Projectivy wallpaper plugin API (`aidl/`, `src/tv/projectivy/`)

The interface and data classes for Projectivy Launcher's wallpaper plugins come from
[spocky/projectivy-plugin-wallpaper-provider](https://github.com/spocky/projectivy-plugin-wallpaper-provider)
(Apache License 2.0). The `.aidl` file is copied as published. `Wallpaper` and `Event` are Java
rewrites of the Kotlin originals with the same parcel format. Haven isn't affiliated with Projectivy.

## Sound (`res/raw/tick.wav`)

Generated for Haven; covered by the MIT licence.
