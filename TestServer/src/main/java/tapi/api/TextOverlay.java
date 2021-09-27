package tapi.api;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public class TextOverlay extends JPanel {

    MediaTracker tracker;
    Toolkit toolkit;
    BufferedImage image;

    public TextOverlay() {

    }

    private File downloadFile(String url) throws IOException
    {
        UUID uuid = UUID.randomUUID();
        URL website = new URL(url);
        ReadableByteChannel rbc = Channels.newChannel(website.openStream());
        FileOutputStream fos = new FileOutputStream(APIController.baseFilePath + uuid + ".png");
        fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);

        return new File(APIController.baseFilePath + uuid + ".png");
    }

    public String makeTextOverlayFile(String url, String identifierText)
    {
        try {
            File fileImage = downloadFile(url);

            image = ImageIO.read(fileImage);
                    //new URL("https://892962.smushcdn.com/2087382/wp-content/uploads/2020/09/FireBeetle-ESP32-DeepSleep.png?lossy=1&strip=1&webp=1"));*/

            if (image == null)
            {
                return APIController.moveToMD5(fileImage);
            }
            image = addText(image, identifierText);

            UUID uuid = UUID.randomUUID();
            File outputFile = new File(APIController.baseFilePath + uuid);
            ImageIO.write(image, "png", outputFile);

            //now move to MD5
            return APIController.moveToMD5(outputFile);
        } catch (IOException| NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        return "";
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(image.getWidth(), image.getHeight());
    }

    private BufferedImage addText(BufferedImage old, String identifierText) {
        String prefix = "";
        if (identifierText.charAt(0) == '@')
        {
            identifierText = identifierText.substring(1);
            prefix = "@";
        }

        int w = old.getWidth();
        int h = old.getHeight();

        //calc suitable text size
        int fontSize = h/10;

        BufferedImage img = new BufferedImage(
                w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        g2d.drawImage(old, 0, 0, w, h, this);
        g2d.setPaint(Color.blue);
        g2d.setFont(new Font("SansSerif", Font.BOLD, fontSize));
        FontMetrics fm = g2d.getFontMetrics();
        int x = img.getWidth() / 7;
        int x2 = x + fm.stringWidth(prefix);
        int y = h - (h/4 - fm.getHeight()/2);
        g2d.drawString(prefix, x, y);
        g2d.setPaint(Color.MAGENTA);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, fontSize));
        g2d.drawString(identifierText, x2, y);
        g2d.dispose();
        return img;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(image, 0, 0, null);
    }
}