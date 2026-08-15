import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Generates every raster asset the project ships: launcher icons, the TV
 * banner, and the store-listing artwork for both Google Play and the Amazon
 * Appstore.
 *
 * It exists so the assets are reproducible from source rather than being
 * opaque binaries someone has to re-cut by hand every time the mark changes,
 * and so the two stores' differently-sized artwork can never drift apart.
 *
 * Run with the JDK that ships inside Android Studio (no dependencies):
 *
 *     java tools/GenerateAssets.java
 *
 * Everything it writes under app/src/main/res (mipmap buckets, the xhdpi
 * tv_banner.png) and under store/ is generated output — do not hand-edit.
 *
 * Design notes: the mark is a screen outline with a play triangle and a
 * broadcast arc. It deliberately says "video player", not "TV channels" — the
 * app ships with no content of its own and both stores' policy teams look at
 * whether the icon implies a content service (see README "Store policy").
 */
public final class GenerateAssets {

    // Palette — must stay in sync with ui/theme/Color.kt.
    private static final Color BG_TOP    = new Color(0x1B3226);
    private static final Color BG_BOTTOM = new Color(0x0B160F);
    private static final Color ACCENT    = new Color(0x1E7A4C);
    private static final Color ACCENT_2  = new Color(0xED8A3D);
    private static final Color FG        = new Color(0xF1F7F2);

    // Must match res/values/strings.xml's app_name — see fitText() for what
    // goes wrong when this drifts from it.
    private static final String WORDMARK = "IPTV Brother Player";
    private static final String TAGLINE  = "Your playlists, on the big screen";

    public static void main(String[] args) throws IOException {
        File res = new File("app/src/main/res");
        File store = new File("store");

        // Launcher icons, one per density bucket.
        int[][] densities = {
                {48, 0}, {72, 1}, {96, 2}, {144, 3}, {192, 4}
        };
        String[] dirs = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"};
        for (int[] d : densities) {
            int size = d[0];
            File dir = new File(res, dirs[d[1]]);
            write(icon(size, false), new File(dir, "ic_launcher.png"));
            write(icon(size, true), new File(dir, "ic_launcher_round.png"));
        }

        // TV banner: Android TV / Fire TV home-row tile. 320x180 dp, supplied
        // at xhdpi so it stays sharp on 1080p and 4K panels alike.
        write(banner(320, 180), new File(new File(res, "drawable-xhdpi"), "tv_banner.png"));

        // Store listing artwork.
        write(icon(512, false), new File(store, "play-icon-512.png"));
        write(banner(1280, 720), new File(store, "play-tv-banner-1280x720.png"));
        write(icon(114, false), new File(store, "amazon-small-icon-114.png"));
        write(icon(512, false), new File(store, "amazon-large-icon-512.png"));
        write(banner(1280, 720), new File(store, "amazon-feature-1280x720.png"));

        System.out.println("Assets written to app/src/main/res and store/");
    }

    /** Rounded-square (or circular) launcher icon at the given pixel size. */
    private static BufferedImage icon(int size, boolean round) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = prepare(img);

        Shape clip = round
                ? new Ellipse2D.Float(0, 0, size, size)
                : new RoundRectangle2D.Float(0, 0, size, size, size * 0.22f, size * 0.22f);
        g.setClip(clip);
        g.setPaint(new GradientPaint(0, 0, BG_TOP, size, size, BG_BOTTOM));
        g.fill(clip);

        // Diagonal accent wash in the lower corner, gives the flat fill depth
        // without needing a second asset for the dark/light theme split.
        g.setPaint(new GradientPaint(
                0, size * 0.55f, new Color(0, 0, 0, 0),
                size, size, new Color(ACCENT_2.getRed(), ACCENT_2.getGreen(), ACCENT_2.getBlue(), 85)));
        g.fill(clip);

        drawMark(g, size * 0.5f, size * 0.5f, size * 0.62f);
        g.dispose();
        return img;
    }

    /** Wide banner with the mark and wordmark, used for the TV home row. */
    private static BufferedImage banner(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = prepare(img);

        g.setPaint(new GradientPaint(0, 0, BG_TOP, w, h, BG_BOTTOM));
        g.fillRect(0, 0, w, h);

        // Soft radial glow behind the mark so it reads against the flat panel
        // of a TV home screen, where the tile sits next to bright poster art.
        float cx = w * 0.235f, cy = h * 0.5f, r = h * 0.62f;
        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(cx, cy), r,
                new float[]{0f, 1f},
                new Color[]{new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 70), new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 0)}));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));

        drawMark(g, cx, cy, h * 0.52f);

        float tx = w * 0.40f;
        float maxTextWidth = w - tx - w * 0.03f;

        g.setColor(FG);
        FontMetrics fm = fitText(g, WORDMARK, Font.BOLD, Math.round(h * 0.155f), maxTextWidth);
        g.drawString(WORDMARK, tx, cy + fm.getAscent() * 0.36f - fm.getDescent() * 0.2f);

        g.setColor(new Color(0xA8B4C8));
        fitText(g, TAGLINE, Font.PLAIN, Math.round(h * 0.072f), maxTextWidth);
        g.drawString(TAGLINE, tx + 2, cy + fm.getAscent() * 0.36f + h * 0.115f);

        g.dispose();
        return img;
    }

    /**
     * Sets `g`'s font to the largest size (down to a floor, so a name that
     * genuinely cannot fit degrades to overlapping rather than vanishing at
     * size zero) at which `text` fits within `maxWidth`, and returns the
     * resulting FontMetrics.
     *
     * Exists because the wordmark used to be a literal `drawString("IPTV
     * Player", ...)` sized for that specific string — harmless until the app
     * was renamed to "IPTV Brother Player" and every generated banner kept
     * shipping the old, five-characters-shorter name, silently, because
     * nothing about a fixed size+string pairing can fail loudly when the
     * string changes elsewhere.
     */
    private static FontMetrics fitText(Graphics2D g, String text, int style, int startSize, float maxWidth) {
        int size = startSize;
        FontMetrics fm;
        do {
            g.setFont(new Font(Font.SANS_SERIF, style, size));
            fm = g.getFontMetrics();
            size -= 1;
        } while (fm.stringWidth(text) > maxWidth && size >= 8);
        return fm;
    }

    /**
     * The mark itself: a screen outline, a play triangle, and a broadcast arc
     * radiating from the top-right. Drawn in vector ops so it scales to any
     * size without a separate source file per density.
     */
    private static void drawMark(Graphics2D g, float cx, float cy, float size) {
        float w = size, h = size * 0.68f;
        float x = cx - w / 2f, y = cy - h / 2f;
        float stroke = Math.max(1.5f, size * 0.075f);

        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(new GradientPaint(x, y, ACCENT, x + w, y + h, ACCENT_2));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, size * 0.16f, size * 0.16f));

        // Play triangle, optically centred (a geometrically centred triangle
        // reads as sitting too far left).
        float t = size * 0.26f;
        Path2D.Float tri = new Path2D.Float();
        float tcx = cx + t * 0.12f;
        tri.moveTo(tcx - t * 0.5f, cy - t * 0.62f);
        tri.lineTo(tcx + t * 0.72f, cy);
        tri.lineTo(tcx - t * 0.5f, cy + t * 0.62f);
        tri.closePath();
        g.setColor(FG);
        g.fill(tri);

        // Broadcast arcs off the top-right corner.
        g.setPaint(ACCENT);
        g.setStroke(new BasicStroke(stroke * 0.72f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 1; i <= 2; i++) {
            float ar = size * (0.14f + 0.11f * i);
            g.draw(new Arc2D.Float(x + w - ar, y - ar, ar * 2, ar * 2, 10, 70, Arc2D.OPEN));
        }
    }

    private static Graphics2D prepare(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    private static void write(BufferedImage img, File out) throws IOException {
        File parent = out.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        ImageIO.write(img, "png", out);
        System.out.println("  " + out.getPath() + "  (" + img.getWidth() + "x" + img.getHeight() + ")");
    }
}
