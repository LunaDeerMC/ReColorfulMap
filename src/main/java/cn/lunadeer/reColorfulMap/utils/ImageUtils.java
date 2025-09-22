package cn.lunadeer.reColorfulMap.utils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;

public class ImageUtils {

    /**
     * Load an image from a file.
     *
     * @param imageFile The file to load the image from.
     * @return The loaded image.
     * @throws IOException If an error occurs while reading the image.
     */
    public static BufferedImage loadImage(File imageFile) throws IOException {
        return ImageIO.read(imageFile);
    }

    /**
     * Load an image from a file.
     *
     * @param imagePath The path to the image file.
     * @return The loaded image.
     * @throws IOException If an error occurs while reading the image.
     */
    public static BufferedImage loadImage(String imagePath) throws IOException {
        File imageFile = new File(imagePath);
        return ImageIO.read(imageFile);
    }

    /**
     * Save an image to a file.
     *
     * @param image      The image to save.
     * @param outputFile The file to save the image to.
     * @param format     The format of the image.
     * @throws IOException If an error occurs while writing the image.
     */
    public static void saveImage(BufferedImage image, File outputFile, String format) throws IOException {
        if (!outputFile.getParentFile().exists() && !outputFile.getParentFile().mkdirs()) {
            throw new IOException("Failed to create parent directory: %s".formatted(outputFile.getParentFile()));
        }
        ImageIO.write(image, format, outputFile);
    }

    /**
     * Save an image to a file.
     *
     * @param image      The image to save.
     * @param outputPath The path to save the image to.
     * @param format     The format of the image.
     * @throws IOException If an error occurs while writing the image.
     */
    public static void saveImage(BufferedImage image, String outputPath, String format) throws IOException {
        File outputFile = new File(outputPath);
        saveImage(image, outputFile, format);
    }

    /**
     * Decodes a Base64 encoded string to a BufferedImage.
     *
     * @param base64 The Base64 encoded string representing the image.
     * @return The decoded BufferedImage.
     * @throws IOException If an error occurs while decoding the image.
     */
    public static BufferedImage decodeBase64ToImage(byte[] base64) throws IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(base64);
        return ImageIO.read(bis);
    }

    /**
     * Encodes a BufferedImage to a Base64 encoded string.
     *
     * @param image  The BufferedImage to encode.
     * @param format The format of the image (e.g., "png", "jpg").
     * @return The Base64 encoded string representing the image.
     * @throws IOException If an error occurs while encoding the image.
     */
    public static byte[] encodeImageToBase64(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream ba_os = new ByteArrayOutputStream();
        ImageIO.write(image, format, ba_os);
        return ba_os.toByteArray();
    }

    /**
     * Resizes a BufferedImage by a given scale.
     *
     * @param image The original BufferedImage to resize.
     * @param scale The scale factor to resize the image by.
     * @return The resized BufferedImage.
     */
    public static BufferedImage resize(BufferedImage image, float scale) {
        int new_width = (int) (image.getWidth() * scale);
        int new_height = (int) (image.getHeight() * scale);
        BufferedImage newImage = new BufferedImage(new_width, new_height, BufferedImage.TYPE_INT_ARGB);
        newImage.getGraphics().drawImage(image, 0, 0, new_width, new_height, null);
        return newImage;
    }

    /**
     * Centers a BufferedImage within a new image of specified dimensions.
     *
     * @param image The original BufferedImage to center.
     * @param width The width of the new image.
     * @param height The height of the new image.
     * @return The centered BufferedImage.
     */
    public static BufferedImage center(BufferedImage image, int width, int height) {
        int image_width = image.getWidth();
        int image_height = image.getHeight();
        BufferedImage newImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        newImage.getGraphics().drawImage(image, (width - image_width) / 2, (height - image_height) / 2, null);
        return newImage;
    }

    /**
     * Creates a thumbnail of a BufferedImage with a maximum dimension of 128x128 pixels.
     *
     * @param img The original BufferedImage to create a thumbnail from.
     * @return The thumbnail BufferedImage.
     */
    public static BufferedImage thumb(BufferedImage img) {
        double scale;
        if (img.getWidth() > img.getHeight()) {
            scale = 128.0 / img.getWidth();
        } else {
            scale = 128.0 / img.getHeight();
        }
        return center(resize(img, (float) scale), 128, 128);
    }

}
