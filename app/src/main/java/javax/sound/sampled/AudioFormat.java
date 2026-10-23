package javax.sound.sampled;

/**
 * Android n'a pas javax.sound : JDA ne s'en sert que pour décrire le format PCM de ses gestionnaires audio
 * (AudioSendHandler.INPUT_FORMAT, AudioReceiveHandler.OUTPUT_FORMAT). Sans cette classe, charger ces interfaces fait
 * planter l'application. On fournit audio déjà encodé en Opus : seul ce descripteur est nécessaire.
 */
public class AudioFormat {
    private final float sampleRate;
    private final int sampleSizeInBits;
    private final int channels;
    private final boolean signed;
    private final boolean bigEndian;

    public AudioFormat(float sampleRate, int sampleSizeInBits, int channels, boolean signed, boolean bigEndian) {
        this.sampleRate = sampleRate;
        this.sampleSizeInBits = sampleSizeInBits;
        this.channels = channels;
        this.signed = signed;
        this.bigEndian = bigEndian;
    }

    public float getSampleRate() { return sampleRate; }
    public int getSampleSizeInBits() { return sampleSizeInBits; }
    public int getChannels() { return channels; }
    public int getFrameSize() { return channels * ((sampleSizeInBits + 7) / 8); }
    public float getFrameRate() { return sampleRate; }
    public boolean isBigEndian() { return bigEndian; }
    public boolean isSigned() { return signed; }
}
