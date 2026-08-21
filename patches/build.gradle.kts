import org.gradle.jvm.tasks.Jar

group = "io.github.deadlybytes.revanced"

tasks.withType<Jar>().configureEach {
    archiveBaseName.set("redflagdeals-revanced-patches")
}

patches {
    about {
        name = "RedFlagDeals ReVanced Patches"
        description = "Compatibility fixes for the legacy RedFlagDeals Forums app"
        source = "https://github.com/Deadly-Bytes/redflagdeals-revanced-patches"
        author = "Deadly-Bytes"
        contact = ""
        website = "https://github.com/Deadly-Bytes/redflagdeals-revanced-patches"
        license = "GNU General Public License v3.0"
    }
}
