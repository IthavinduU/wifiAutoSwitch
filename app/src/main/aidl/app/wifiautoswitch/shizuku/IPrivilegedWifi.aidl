package app.wifiautoswitch.shizuku;

// Runs inside a Shizuku "user service" process with the shell (adb) identity.
// Deliberately tiny: no shell commands, no passwords, only two fixed operations.
interface IPrivilegedWifi {
    // Reserved by Shizuku: called when the service is unbound with remove=true.
    void destroy() = 16777114;

    // Newline-separated SSIDs of networks already saved on the phone.
    String listSavedSsids() = 1;

    // Joins the saved network with this SSID using its existing profile.
    // Returns "ok:<networkId>", "notfound" or "error:<message>".
    String connectSaved(String ssid) = 2;
}
