package android.net;

/**
 * Compile-time stand-in for the hidden framework class (Android 5.0+). It is not packaged: build.sh drops everything under
 * android/ from the jar, the guest framework provides the real class.
 */
public abstract class NetworkAgent {
    public NetworkAgent(android.os.Looper looper, android.content.Context context, String logTag, NetworkInfo ni,
                        NetworkCapabilities nc, LinkProperties lp, int score) { }
    protected abstract void unwanted();
    public void sendNetworkInfo(NetworkInfo networkInfo) { }
}
