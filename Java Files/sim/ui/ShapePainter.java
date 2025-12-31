package sim.ui;


import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import sim.model.Flight.ShapeType;


/**
 * Utility class responsible for rendering passenger shapes with borders.
 */
public class ShapePainter {
    private ShapePainter() {
        // Prevent instantiation
    }


    /**
     * Draws a filled shape with a thicker colored border.
     *
     * @param g            the Graphics context
     * @param type         the shape type
     * @param x            top-left x
     * @param y            top-left y
     * @param w            width
     * @param h            height
     * @param borderColor  color of the border
     */
    public static void paintShape(Graphics g,
                                  ShapeType type,
                                  int x, int y,
                                  int w, int h,
                                  Color borderColor) {
        Graphics2D g2 = (Graphics2D) g;
        Color originalColor = g2.getColor();
        java.awt.Stroke originalStroke = g2.getStroke();


        // Fill shape
        switch (type) {
            case CIRCLE:
                g2.fillOval(x, y, w, h);
                break;
            case TRIANGLE:
                int[] xsT = { x + w / 2, x, x + w };
                int[] ysT = { y, y + h, y + h };
                g2.fillPolygon(xsT, ysT, 3);
                break;
            case SQUARE:
                g2.fillRect(x, y, w, h);
                break;
case DIAMOND: {
int[] xsD = { x + w/2, x + w, x + w/2, x };
int[] ysD = { y, y + h/2, y + h, y + h/2 };
g2.fillPolygon(xsD, ysD, 4);
break;
}
case STAR: {
int cx = x + w/2;
int cy = y + h/2;
int rOuter = Math.min(w, h) / 2;
int rInner = rOuter / 2;

int[] xsS = new int[10];
int[] ysS = new int[10];
for (int i = 0; i < 10; i++) {
double angle = -Math.PI / 2 + i * (Math.PI / 5);
int r = (i % 2 == 0) ? rOuter : rInner;
xsS[i] = cx + (int)Math.round(Math.cos(angle) * r);
ysS[i] = cy + (int)Math.round(Math.sin(angle) * r);
}
g2.fillPolygon(xsS, ysS, 10);
break;
}
case HEXAGON: {
int cx = x + w/2;
int cy = y + h/2;
int r = Math.min(w, h) / 2;

int[] xsH = new int[6];
int[] ysH = new int[6];
for (int i = 0; i < 6; i++) {
double angle = Math.PI / 6 + i * (Math.PI / 3);
xsH[i] = cx + (int)Math.round(Math.cos(angle) * r);
ysH[i] = cy + (int)Math.round(Math.sin(angle) * r);
}
g2.fillPolygon(xsH, ysH, 6);
break;
}

            default:
                g2.fillOval(x, y, w, h);
        }


        // Draw thicker border
        g2.setColor(borderColor);
        g2.setStroke(new BasicStroke(2.5f));  // <-- thicker border


        switch (type) {
            case CIRCLE:
                g2.drawOval(x, y, w, h);
                break;
            case TRIANGLE:
                int[] xsB = { x + w / 2, x, x + w };
                int[] ysB = { y, y + h, y + h };
                g2.drawPolygon(xsB, ysB, 3);
                break;
            case SQUARE:
                g2.drawRect(x, y, w, h);
                break;
                case DIAMOND: {
int[] xsD = { x + w/2, x + w, x + w/2, x };
int[] ysD = { y, y + h/2, y + h, y + h/2 };
g2.drawPolygon(xsD, ysD, 4);
break;
}
case STAR: {
int cx = x + w/2;
int cy = y + h/2;
int rOuter = Math.min(w, h) / 2;
int rInner = rOuter / 2;

int[] xsS = new int[10];
int[] ysS = new int[10];
for (int i = 0; i < 10; i++) {
double angle = -Math.PI / 2 + i * (Math.PI / 5);
int r = (i % 2 == 0) ? rOuter : rInner;
xsS[i] = cx + (int)Math.round(Math.cos(angle) * r);
ysS[i] = cy + (int)Math.round(Math.sin(angle) * r);
}
g2.drawPolygon(xsS, ysS, 10);
break;
}
case HEXAGON: {
int cx = x + w/2;
int cy = y + h/2;
int r = Math.min(w, h) / 2;

int[] xsH = new int[6];
int[] ysH = new int[6];
for (int i = 0; i < 6; i++) {
double angle = Math.PI / 6 + i * (Math.PI / 3);
xsH[i] = cx + (int)Math.round(Math.cos(angle) * r);
ysH[i] = cy + (int)Math.round(Math.sin(angle) * r);
}
g2.drawPolygon(xsH, ysH, 6);
break;
}
            default:
                g2.drawOval(x, y, w, h);
        }


        // Reset graphics state
        g2.setStroke(originalStroke);
        g2.setColor(originalColor);
    }
}
