package launch;

import cpw.mods.modlauncher.Launcher;

public class Alaphant {
    public static void main(String[] args) {
        System.setProperty("mixin.env.disableRefMap", "true");

        Launcher.main("--launchTarget", "alaphant");
    }
}
