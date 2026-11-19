# HyperSpring roadmap

What is planned for the next versions. Plans may change. Suggestions are welcome in [Issues](https://github.com/olilio3/HyperSpring/issues).

The bar for getting on this list: a fair number of people will use it, it can be maintained when HyperOS changes, and it costs no battery or smoothness to those who don't use it. A flashy effect that gets turned off after two days doesn't make it. Before starting anything for the home screen, check whether Kashi's Launcher Mod or HyperCeiler already has it.

Found a bug? Use **About → Report a bug**: it copies the log and opens the issue with your details filled in.

## Next
- [ ] **Release what has piled up.** There has been no release since 2.9.1: 2.9.2 to 2.12.x only live on the branch. First, remove from the CHANGELOG what no longer exists (glass notifications, focus when switching pages).
- [ ] **Opening and closing apps, better than ever:** make it feel like Xiaomi made it.
  - **No sudden jumps:** the capture has to be ready before the window moves. *Since 2.12.x the first capture is prepared when the launcher starts (first close went from 354 ms to 9 ms).*
  - **Like stock:** the same springs, timings, corners and shadows as HyperOS. Needs a side-by-side check by eye.
  - **A show:** more physics and more light in Depth, without losing smoothness on mid-range phones.
- [ ] **Glass dock out of beta:** it has been a beta since 2.12.0.
- [ ] **Hot reload (libxposed API 102):** module updates and feature switches apply without restarting the launcher or System UI.
  - The module still has to learn to "unmount" itself: stop its threads, remove its listeners and reinstall the hooks with the new code.
  - This is the most delicate item here: leaving anything half-done crashes System UI. It will ship in a beta first.
- [x] **POCO Launcher support**, bringing in the work from the community fork ([ToolDroidYT/hyperspring](https://github.com/ToolDroidYT/hyperspring)). *Released in 3.0.0.*

## Planned
Not started, in no fixed order.

- [ ] **Highlight that follows your finger in the control center:** the glass glow moves towards where you touch, as folders already do (**Touch glow**). A test version from 2026-11-16 was never saved to any branch: it has to be redone.
- [ ] **Genie from notifications:** opening a notification makes the app come out of the card. First check what the curtain already does in that transition.
- [ ] **Genie into folders:** closing an app that lives inside a folder sends the window back to the folder.
- [ ] **Genie on widgets:** opening an app from a widget makes the window come out of the widget.
- [ ] **Predictive back gesture with physics:** while dragging, the screen shrinks and tilts with your finger (with the curtain physics); on release it springs back or closes. On the Redmi 14C HyperOS ships no predictive back animations, so they have to be created.
- [ ] **Icons that spring out of the way** when you drag one across the home screen, instead of sliding in a straight line.
- [ ] **A tick when the widget stack changes**, like the ones the genie (on landing) and folders (on resize) already have.

## Dropped
Ideas that were on the list and were removed, with the reason:
- **Glass notifications** (removed 2026-11-17). People prefer HyperOS's own blur, and painting every card stuttered while scrolling on mid-range phones.
- **Springy notifications** (2026-11-18). Same reason: notifications stay the way HyperOS makes them.
- **Focus or bounce when switching between notifications and the control center** (2026-11-17). The opening progress jumps (from 0 to 0.52) when switching pages: there is no reliable signal to sync anything to.
- **Magnifier on the control center sliders** (2026-11-18). It depends on System UI plugin classes that change with every version, like the rubber effect.
- **Rubber effect on the volume and brightness sliders.** Same reason: a lot of upkeep for a detail.
- **Dark mode circular wave** and **home screen pages that stretch the icons** (2026-11-18). Flashy effects that get turned off within days; the second also clashes with the transitions of Kashi's launcher.
- **Redesigned app** (2026-11-18). The current design stays; the entrances, the app's predictive back and the animated backgrounds are already there.
- **In-app preview.** It means duplicating the animations outside the launcher; with presets and live changes, it adds little.
- **Widget stacks and folders on the stock launcher.** They stay on Kashi's launcher only, which is where stacks and folder sizes exist.
- **Elastic overscroll in every app's lists.** It needs hooking the Android framework in every app: a lot of risk for a small effect.
- **Gyroscope wallpaper parallax.** It keeps a sensor running on the home screen: battery for decoration.
- **Wallpaper ripple on tap** and **cascade entrance on unlock.** Eye-catching, but they get old fast and load the GPU right when the phone is already busy. Unlock zoom covers this instead.
- **More recents styles (fan, 3D stack).** What people asked for was a way to turn the folding off, and that exists.

## Done
- [x] 2.9.2 to 2.12.x (not released yet): full code review, automatic GitHub builds, feature wheel, automatic pause (heat, games, battery saver), glass styles, system modes (HyperOS 4, iOS 26, iOS 27), glass dock (beta), unlock zoom, automatic genie resolution, "How is my phone doing?", refraction on folders and the app intro. Details in the CHANGELOG.
- [x] 2.7.0: springy shortcut menu with a liquid-glass rim, quick settings tile to pause HyperSpring, Android animation speed and `HS1:` codes to share settings.
- [x] 2.2.0: the welcome text no longer slides under the icon, and the About icon no longer gets cut off.
- [x] 2.1.0: Depth style, visual spring editor, apps without genie, in-app updates and what's new, and the redesigned app.
- [x] 2.0.0: libxposed API 102 with two engines (the old API 93 one as a separate APK), one-tap presets, settings export and import.
- [x] 1.0.3: smoother genie, closer to HyperOS's own.
- [x] 1.0.2: the control center glass stopped working after a while; recents cards only fold when moved with a finger.
- [x] 1.0.0: the APK was built with targetSdk 30 and Android 16 refused to install it.
