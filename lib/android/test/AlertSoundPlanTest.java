package dev.phonestation.adbkeep;

final class AlertSoundPlanTest {
    public static void main(String[] args) {
        if (AlertSoundPlan.PLAYED != 1 || AlertSoundPlan.SOUND_FAILED != 2
                || AlertSoundPlan.MISSING != 3 || AlertSoundPlan.POSTED_SILENT != 4) {
            throw new AssertionError("broadcast codes");
        }
        String system = "content://media/internal/audio/media/12";
        AlertSoundPlan followed = AlertSoundPlan.choose(null, null, system);
        AlertSoundPlan empty = AlertSoundPlan.choose("  ", null, system);
        if (followed.kind != AlertSoundPlan.URI || !system.equals(followed.value)
                || empty.kind != AlertSoundPlan.URI) {
            throw new AssertionError("system uri");
        }
        AlertSoundPlan file = AlertSoundPlan.choose(
                null, null, "file:///system/media/audio/notifications/Pixies.ogg");
        AlertSoundPlan path = AlertSoundPlan.choose(
                "/system/media/audio/notifications/Bell.ogg",
                null,
                system);
        if (file.kind != AlertSoundPlan.FILE
                || !"/system/media/audio/notifications/Pixies.ogg".equals(file.value)
                || path.kind != AlertSoundPlan.FILE
                || !"/system/media/audio/notifications/Bell.ogg".equals(path.value)) {
            throw new AssertionError("file");
        }
        AlertSoundPlan givenUri = AlertSoundPlan.choose(
                "content://media/internal/audio/media/9",
                "/system/media/audio/notifications/Pixies.ogg",
                system);
        if (givenUri.kind != AlertSoundPlan.URI
                || !"content://media/internal/audio/media/9".equals(givenUri.value)) {
            throw new AssertionError("explicit uri");
        }
        AlertSoundPlan stored = AlertSoundPlan.choose(
                null, "content://media/internal/audio/media/3", system);
        AlertSoundPlan storedFile = AlertSoundPlan.choose(
                "  ", "file:///system/media/audio/notifications/Bell.ogg", null);
        if (stored.kind != AlertSoundPlan.URI
                || !"content://media/internal/audio/media/3".equals(stored.value)
                || storedFile.kind != AlertSoundPlan.FILE
                || !"/system/media/audio/notifications/Bell.ogg".equals(storedFile.value)) {
            throw new AssertionError("stored");
        }
        AlertSoundPlan silent = AlertSoundPlan.choose(null, AlertSoundPlan.SILENT_TOKEN, system);
        AlertSoundPlan silentWins = AlertSoundPlan.choose(" ", AlertSoundPlan.SILENT_TOKEN, null);
        AlertSoundPlan explicitOverSilent = AlertSoundPlan.choose(
                "/system/media/audio/notifications/Bell.ogg", AlertSoundPlan.SILENT_TOKEN, null);
        if (silent.kind != AlertSoundPlan.SILENT
                || silentWins.kind != AlertSoundPlan.SILENT
                || explicitOverSilent.kind != AlertSoundPlan.FILE) {
            throw new AssertionError("silent");
        }
        AlertSoundPlan missing = AlertSoundPlan.choose(null, null, null);
        AlertSoundPlan word = AlertSoundPlan.choose("", null, "null");
        AlertSoundPlan blank = AlertSoundPlan.choose(null, null, "  ");
        if (missing.kind != AlertSoundPlan.MISSING
                || word.kind != AlertSoundPlan.MISSING
                || blank.kind != AlertSoundPlan.MISSING) {
            throw new AssertionError("missing");
        }
        String withQuery = "content://media/internal/audio/media/223?title=Pixies&canonical=1";
        String plain = "content://media/internal/audio/media/223";
        if (!AlertSoundPlan.sameTone(withQuery, plain)
                || !AlertSoundPlan.sameTone(plain, plain)
                || !AlertSoundPlan.sameTone(null, null)
                || AlertSoundPlan.sameTone(withQuery, plain + "9")
                || AlertSoundPlan.sameTone(null, plain)
                || AlertSoundPlan.sameTone(withQuery, null)) {
            throw new AssertionError("same tone");
        }
        System.out.println("AlertSoundPlanTest ok");
    }
}
