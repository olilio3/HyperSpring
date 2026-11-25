package com.icehome.up.cc;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * Shader de cristal común a carpetas y centro de control (AGSL, Android 13+).
 *
 * Capas, de abajo arriba (todas dentro de la forma redondeada, con antialias en el canto):
 *  1. Lo que hay detrás (fondo de pantalla o captura de debajo del centro de control):
 *     - en todo el interior con opacidad {@code fill} y una ligera lupa ({@code mag});
 *     - refracción del borde como la lente de Kyant0/AndroidLiquidGlass (backdrop, Apache-2.0,
 *       {@code RoundedRectRefractionWithDispersionShaderString}): en una franja de altura
 *       {@code refrH} desde el borde, el fondo se toma desplazado hacia dentro
 *       {@code circleMap(1 - d/refrH)·refrAmt} a lo largo del gradiente del rectángulo redondeado
 *       (con radio ×1,5 para que la normal gire suave en las esquinas), con circleMap(x) =
 *       1 - √(1 - x²): un bisel circular, casi nada lejos del borde y mucho pegado a él;
 *     - dispersión cromática de la misma lente: 7 muestras (rojo, naranja, amarillo, verde, cian,
 *       azul, violeta) separadas según x·y/(ancho·alto), es decir solo en las esquinas.
 *  2. Canto iluminado con el color de lo que hay detrás: el cristal "llega" hasta el borde y
 *     la luz que lo atraviesa sale teñida. Más fuerte arriba-izquierda, eco abajo-derecha.
 *  3. Luz de la lente: brillo según la dirección de la luz, contraluz, línea cáustica y un leve
 *     surco oscuro hacia dentro (grosor del cristal).
 */
public final class GlassShader {

    static final String AGSL =
            "uniform shader wall;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float band;\n"
            + "uniform float disp;\n"
            + "uniform float dispersion;\n"
            + "uniform float2 origin;\n"      // px del fondo en el punto local (0,0)
            + "uniform float2 scale;\n"       // px del fondo por px local
            + "uniform float hasWall;\n"
            + "uniform float fill;\n"
            + "uniform float bandAlpha;\n"
            + "uniform float tone;\n"
            + "uniform float sat;\n"
            + "uniform float darker;\n"       // oscurece las luces altas (como el 'darker' de HyperOS 4)
            + "uniform float whiteMix;\n"
            + "uniform float edgeLift;\n"     // canto esmerilado: se aclara hacia el borde
            + "uniform float stretch;\n"      // canto: estira hacia dentro el color del borde (0..1)
            + "uniform float overshoot;\n"    // ...tomándolo hasta 'overshoot' px por fuera del borde
            + "uniform float edgeSat;\n"      // saturación extra en el canto
            + "uniform float mag;\n"
            + "uniform float rimw;\n"         // núcleo del canto de luz (px)
            + "uniform float glow;\n"         // halo del canto hacia dentro (px)
            + "uniform float rimGain;\n"
            + "uniform float innerDark;\n"    // línea interior tenue bajo el canto (contorno nítido)
            + "uniform float light;\n"
            + "uniform float active;\n"
            + "uniform float refrH;\n"       // altura de la lente desde el borde (px; 0 = sin)
            + "uniform float refrAmt;\n"     // desplazamiento máximo hacia dentro (px)
            + "uniform float chroma;\n"      // dispersión cromática de la lente (0 = sin)
            + "uniform float baked;\n"       // 1 = el fondo ya trae vivid() hecho en la CPU (ver baked())
            + "uniform float pxs;\n"         // px de pantalla por px local (0 = 1): antialias justo al escalar
            + "uniform float bevel;\n"       // 0 = bisel redondo; 1 = centro plano y curva pegada al borde (iOS 26)
            + "uniform float fresnel;\n"     // reflejo fino de Fresnel en el canto (0 = sin)
            + "uniform float shade;\n"       // sombra interior del borde de abajo (0 = sin)
            + "uniform float depth;\n"       // lente en cúpula: la dirección se abre hacia fuera desde el centro (0 = sin)
            + "float sd(float2 p, float2 b, float r) {\n"
            + "  float2 q = abs(p) - b + r;\n"
            + "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "half4 over(half4 top, half4 bot) { return top + bot * (1.0 - top.a); }\n"
            // De Kyant0/AndroidLiquidGlass (Apache-2.0): gradiente del rectángulo redondeado y bisel.
            + "float2 gradSd(float2 coord, float2 halfSize, float r) {\n"
            + "  float2 cc = abs(coord) - (halfSize - float2(r));\n"
            + "  if (cc.x >= 0.0 || cc.y >= 0.0) return sign(coord) * normalize(max(cc, float2(1e-4)));\n"
            + "  float gx = step(cc.y, cc.x);\n"
            + "  return sign(coord) * float2(gx, 1.0 - gx);\n"
            + "}\n"
            + "float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }\n"
            + "float3 at(float2 q) { return float3(wall.eval(q).rgb); }\n"
            // Suavizado B-spline con 4 lecturas lineales: en la franja del borde, donde el fondo se
            // amplía, quita las facetas del filtro bilineal.
            + "float3 atS(float2 q) {\n"
            + "  float2 t = q - 0.5, i = floor(t), f = t - i, f2 = f * f, f3 = f2 * f;\n"
            + "  float2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0, w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;\n"
            + "  float2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0, w3 = f3 / 6.0;\n"
            + "  float2 s0 = w0 + w1, s1 = w2 + w3, c0 = i - 0.5 + w1 / s0, c1 = i + 1.5 + w3 / s1;\n"
            + "  return s0.y * (s0.x * at(float2(c0.x, c0.y)) + s1.x * at(float2(c1.x, c0.y)))\n"
            + "       + s1.y * (s0.x * at(float2(c0.x, c1.y)) + s1.x * at(float2(c1.x, c1.y)));\n"
            + "}\n"
            // Ruido de gradiente entrelazado: rompe las bandas de 8 bits del fondo.
            + "float ign(float2 q) { return fract(52.9829189 * fract(dot(q, float2(0.06711056, 0.00583715)))); }\n"
            // Saturar sin salirse de 0..1 manteniendo el tono (no pasa a amarillo el naranja).
            + "float3 satKeep(float3 c, float s) {\n"
            + "  float l = dot(c, float3(0.2126, 0.7152, 0.0722));\n"
            + "  float3 v = mix(float3(l), c, s);\n"
            + "  float hi = max(max(v.r, v.g), v.b), lo = min(min(v.r, v.g), v.b);\n"
            + "  float f = min(1.0, min((1.0 - l) / max(hi - l, 1e-4), l / max(l - lo, 1e-4)));\n"
            + "  return mix(float3(l), v, f);\n"
            + "}\n"
            // Hombro suave: las luces se acercan a 1 sin recortarse en manchas blancas planas.
            + "float3 shoulder(float3 x) {\n"
            + "  return mix(x, 0.8 + 0.2 * (1.0 - exp((0.8 - x) / 0.2)), step(float3(0.8), x));\n"
            + "}\n"
            + "float3 vivid(float3 c) {\n"
            + "  float3 v = satKeep(c, sat);\n"
            + "  float l2 = dot(v, float3(0.2126, 0.7152, 0.0722));\n"
            + "  v *= 1.0 - darker * smoothstep(0.6, 1.0, l2);\n"
            + "  v = shoulder(v * tone);\n"
            + "  return mix(v, float3(1.0), whiteMix);\n"
            + "}\n"
            + "half4 main(float2 xy) {\n"
            + "  float2 h = size * 0.5;\n"
            + "  float2 p = xy - h;\n"
            + "  float d = -sd(p, h, radius);\n"
            // Cobertura centrada en el borde (medio píxel a cada lado), como drawPath: sin escalones
            // ni filo entre el cristal y lo que se pinta con la misma forma.
            // Si la pieza está escalada (animación), medio píxel de pantalla no es medio píxel local.
            + "  float ps = pxs > 0.0 ? pxs : 1.0;\n"
            + "  if (d * ps <= -0.5) return half4(0.0);\n"
            + "  float aa = clamp(d * ps + 0.5, 0.0, 1.0);\n"
            + "  d = max(d, 0.0);\n"
            + "  float t = clamp(1.0 - d / max(band, 0.001), 0.0, 1.0);\n"   // canto 0 (estilo limpio) sin dividir entre 0
            // La normal (4 sd) solo se usa cerca del borde: en la franja, el núcleo del canto o donde
            // el halo aún se ve (exp(-6)·0,55 < 1/255). Más adentro vale 0 y nos ahorramos el cálculo.
            + "  bool edge = d < max(band, max(rimw * 6.5, glow * 6.0));\n"
            // Pasada de luz (sin relleno) lejos del borde: no hay nada que pintar.
            + "  if (!edge && fill <= 0.0) return half4(0.0);\n"
            + "  float2 n = float2(0.0);\n"
            + "  if (edge) {\n"
            // Gradiente exacto de sd() (antes, 4 sd() más por diferencias): mismo resultado y ~45
            // operaciones menos por píxel del borde. En la esquina apunta desde el centro del arco.
            + "    float2 qn = abs(p) - h + radius;\n"
            + "    n = (qn.x > 0.0 && qn.y > 0.0) ? sign(p) * normalize(qn)\n"
            + "        : (qn.x > qn.y ? float2(sign(p.x), 0.0) : float2(0.0, sign(p.y)));\n"
            + "  }\n"
            + "  float f = dot(n, float2(-0.55, -0.835));\n"   // + arriba-izquierda, - abajo-derecha
            + "  float k = t * t * (3.0 - 2.0 * t);\n"
            + "  half4 acc = half4(0.0);\n"
            + "  float3 tint = float3(1.0);\n"
            // Con fill y bandAlpha a 0 (pasada de lente del CC) el fondo solo tiñe el canto: lejos
            // del borde no hace falta leerlo.
            + "  if (hasWall > 0.5 && (fill > 0.0 || bandAlpha > 0.0 || edge)) {\n"
            + "    float2 dv = -n * disp * k - p * mag + n * (d * stretch + overshoot) * k;\n"
            + "    float2 lens = float2(0.0);\n"
            + "    if (refrH > 0.0 && d < refrH) {\n"
            + "      float gr = min(radius * 1.5, min(h.x, h.y));\n"
            + "      float x = 1.0 - d / refrH;\n"
            + "      float cm = circleMap(x);\n"
            // Bisel tipo squircle: casi nada hasta muy cerca del borde y ahí mucho (centro plano).
            + "      if (bevel > 0.0) { float x4 = x * x * x * x; cm = mix(cm, 1.0 - pow(max(1.0 - x4, 0.0), 0.25), bevel); }\n"
            + "      float2 gd = normalize(gradSd(p, h, gr));\n"
            // Con profundidad: la lente se apaga suave hacia dentro y su dirección se abre hacia
            // fuera desde el centro (aspecto de cúpula). Sin ella, igual que siempre.
            + "      if (depth > 0.0) {\n"
            + "        float u = 1.0 - x;\n"
            + "        float fade = 1.0 - u * u * (3.0 - 2.0 * u);\n"
            + "        cm *= fade;\n"
            + "        if (dot(p, p) > 1e-4) gd = normalize(gd + depth * fade * normalize(p));\n"
            + "      }\n"
            + "      lens = -cm * refrAmt * gd;\n"
            + "    }\n"
            + "    float2 base = origin + (xy + dv + lens) * scale;\n"
            + "    float3 raw;\n"
            // Como HyperLight: un 40 % en todo el canto y hasta el 100 % en las esquinas.
            + "    float ci = chroma * (0.4 + 0.6 * abs(p.x * p.y) / (h.x * h.y));\n"
            + "    if (abs(ci) > 0.001 && (lens.x != 0.0 || lens.y != 0.0)) {\n"
            + "      float2 dc = lens * ci * scale;\n"
            + "      float3 r1 = at(base + dc), o = at(base + dc * (2.0 / 3.0)), y = at(base + dc * (1.0 / 3.0));\n"
            + "      float3 gg = at(base), cy = at(base - dc * (1.0 / 3.0)), b = at(base - dc * (2.0 / 3.0)), v = at(base - dc);\n"
            + "      raw = float3(r1.r / 3.5 + o.r / 3.5 + y.r / 3.5 + v.r / 7.0,\n"
            + "                   o.g / 7.0 + y.g / 3.5 + gg.g / 3.5 + cy.g / 3.5,\n"
            + "                   cy.b / 3.0 + b.b / 3.0 + v.b / 3.0);\n"
            + "    } else {\n"
            // Con arcoíris de la lente, la lente no separa colores aquí (si no, una raya de color en
            // el centro de cada lado, donde ci vale 0).
            + "      float2 dd = (chroma > 0.0 ? dv : dv + lens) * dispersion * scale;\n"
            // Separación de menos de medio píxel del fondo (ya desenfocado): no se nota, 1 muestra.
            // B-spline en toda la zona que se dobla, no solo en el canto: si no, entre el canto y
            // refrH se veían facetas del bilineal y un aro donde cambiaba el filtro.
            + "      if (dot(dd, dd) < 0.25) raw = (edge || d < refrH) ? atS(base) : at(base);\n"
            + "      else raw = float3(at(base + dd).r, at(base).g, at(base - dd).b);\n"
            + "    }\n"
            // Recortado a 0..1: el ruido podía sacar la luminancia de rango y satKeep invertía el color.
            + "    raw = clamp(raw + (ign(xy) - 0.5) * (1.0 / 255.0), 0.0, 1.0);\n"
            + "    float3 bg = baked > 0.5 ? raw : vivid(raw);\n"
            // Con k = 0 (lejos del canto) estas dos no cambian nada: se saltan.
            + "    if (k > 0.0) {\n"
            + "      bg = satKeep(bg, 1.0 + edgeSat * k);\n"
            + "      bg = mix(bg, float3(1.0), edgeLift * k * k);\n"
            + "    }\n"
            + "    float a = max(fill, k * bandAlpha);\n"
            + "    acc = half4(half3(bg * a), half(a));\n"
            // El color de detrás a pleno brillo (sin recortar): el canto se tiñe también sobre fondos claros.
            + "    float m = max(max(bg.r, bg.g), max(bg.b, 0.02));\n"
            + "    tint = mix(bg / m, float3(1.0), 0.25);\n"
            + "    float ga = clamp(k * k * max(f, 0.0) * 0.22 * rimGain * min(1.0, bandAlpha * 4.0), 0.0, 1.0);\n"
            + "    acc = over(half4(half3(tint * ga), half(ga)), acc);\n"
            + "  }\n"
            // Sombra interior abajo (n.y > 0 es el borde de abajo): da grosor al cristal.
            + "  if (shade > 0.0 && t > 0.0) {\n"
            + "    acc = over(half4(0.0, 0.0, 0.0, half(shade * t * t * max(n.y, 0.0))), acc);\n"
            + "  }\n"
            + "  if (t > 0.0 && light > 0.0) {\n"
            + "    float lens = t * t * t;\n"
            + "    float key = lens * max(f, 0.0);\n"
            + "    float opp = lens * max(-f, 0.0) * 0.62;\n"
            + "    float ca = (t - 0.62) / 0.11;\n"
            + "    float caustic = exp(-ca * ca) * 0.08 * (0.4 + 0.6 * abs(f));\n"
            + "    float tr = (t - 0.30) / 0.13;\n"
            + "    float trough = exp(-tr * tr) * 0.05 * (1.0 - 0.85 * active) * light;\n"
            + "    float w = clamp((key * 0.72 + opp * 0.38 + caustic) * light * (1.0 + 0.18 * active), 0.0, 1.0);\n"
            + "    acc = over(half4(0.0, 0.0, 0.0, half(trough)), acc);\n"
            + "    float3 col = clamp(float3(1.0) + float3(0.10, 0.02, -0.09) * f * t, 0.0, 1.0);\n"
            + "    acc = over(half4(half3(col * w), half(w)), acc);\n"
            + "  }\n"
            // Canto de luz (como el "bloom stroke" de HyperOS): núcleo fino + halo, brillante en las
            // esquinas arriba-izquierda y abajo-derecha, teñido con el color de detrás.
            + "  if (innerDark > 0.0) {\n"
            + "    float q = (d - rimw * 2.6) / (rimw * 1.3);\n"
            + "    acc = over(half4(0.0, 0.0, 0.0, half(exp(-q * q) * innerDark)), acc);\n"
            + "  }\n"
            + "  if (rimGain > 0.0) {\n"
            + "    float core = 1.0 - smoothstep(rimw * 0.6, rimw * 1.4, d);\n"
            + "    float halo = glow > 0.0 ? exp(-d / glow) * 0.55 : 0.0;\n"
            + "    float la = 0.06 + 1.1 * pow(max(f, 0.0), 1.8) + 0.18 * pow(max(-f, 0.0), 3.0);\n"
            + "    float ra = clamp((core * 0.92 + halo) * la * rimGain, 0.0, 1.0);\n"
            + "    float3 rc = mix(tint, float3(1.0), 0.6);\n"
            + "    acc = over(half4(half3(rc * ra), half(ra)), acc);\n"
            + "  }\n"
            // Reflejo de Fresnel (Schlick): el bisel se tumba hacia el borde (t = 1) y allí refleja casi
            // todo. Línea blanca fina en el lado que mira a la luz y, algo más suave, en el opuesto.
            + "  float lw = rimw * 1.75;\n"
            + "  if (fresnel > 0.0 && d < lw + 1.0) {\n"
            // Normal con radio ×1,5: la luz gira suave en las esquinas, sin pico. Fuerte hacia
            // la luz (exponente bajo: se reparte por todo el lado) y más suave en el opuesto.
            + "    float2 g = normalize(gradSd(p, h, min(radius * 1.5, min(h.x, h.y))));\n"
            + "    float fl = dot(g, float2(-0.55, -0.835));\n"
            + "    float pri = pow(max(fl, 0.0), 0.65) * 0.56;\n"
            + "    float sec = 0.58 * pow(max(-fl, 0.0), 1.1) * 0.34;\n"
            + "    float cov = clamp((lw - d) * ps + 0.5, 0.0, 1.0);\n"
            + "    float sp = clamp(fresnel * 0.57 * (pri + sec) * cov, 0.0, 1.0);\n"
            + "    float3 lc = mix(float3(0.92, 0.96, 1.0), float3(1.0), pri / max(pri + sec, 1e-4));\n"
            + "    acc = over(half4(half3(lc * sp), half(sp)), acc);\n"
            + "  }\n"
            + "  return acc * half(aa);\n"
            + "}\n";

    /**
     * Lente para el contenido de una pieza (el relleno de los sliders), como hace HyperLight: se
     * pone como RenderEffect en la vista del relleno, y {@code size}/{@code offset} dicen dónde está
     * la pieza entera, así que el relleno se curva en los bordes de la pieza y no en los suyos.
     * Misma lente que {@link #AGSL} (Kyant0/AndroidLiquidGlass), sin arcoíris: el relleno es blanco.
     */
    static final String FILL_LENS =
            "uniform shader content;\n"
            + "uniform float2 size;\n"       // tamaño de la pieza entera (px)
            + "uniform float2 offset;\n"     // dónde empieza el relleno dentro de la pieza (px)
            + "uniform float radius;\n"
            + "uniform float refrH;\n"
            + "uniform float refrAmt;\n"
            + "uniform float depth;\n"
            + "float sdRR(float2 p, float2 h, float r) {\n"
            + "  float2 c = abs(p) - (h - float2(r));\n"
            + "  return length(max(c, 0.0)) - r + min(max(c.x, c.y), 0.0);\n"
            + "}\n"
            + "float2 gradRR(float2 p, float2 h, float r) {\n"
            + "  float2 c = abs(p) - (h - float2(r));\n"
            + "  float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);\n"
            + "  if (c.x >= 0.0 || c.y >= 0.0) return s * normalize(max(c, 0.0) + 0.0001);\n"
            + "  float gx = step(c.y, c.x);\n"
            + "  return s * float2(gx, 1.0 - gx);\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "  float2 h = size * 0.5;\n"
            + "  float2 p = coord + offset - h;\n"
            + "  float sd = sdRR(p, h, radius);\n"
            + "  if (-sd >= refrH) return content.eval(coord);\n"
            + "  float t = -min(sd, 0.0) / refrH;\n"
            + "  float fade = 1.0 - t * t * (3.0 - 2.0 * t);\n"
            + "  float x = 1.0 - t;\n"
            + "  float d = (1.0 - sqrt(1.0 - x * x)) * refrAmt * fade;\n"
            + "  float2 g = gradRR(p, h, radius);\n"
            + "  if (dot(p, p) > 1e-4) g = normalize(g + depth * fade * normalize(p));\n"
            + "  return content.eval(coord + d * g);\n"
            + "}\n";

    private static BitmapShader empty;

    /** Crea el shader con un fondo vacío (1×1) ya enlazado; null si el sistema no lo admite. */
    public static RuntimeShader create() {
        RuntimeShader rs = new RuntimeShader(AGSL);
        rs.setInputShader("wall", empty());
        rs.setFloatUniform("hasWall", 0f);
        rs.setFloatUniform("sat", 1f);
        rs.setFloatUniform("darker", 0f);
        rs.setFloatUniform("whiteMix", 0f);
        rs.setFloatUniform("edgeLift", 0f);
        rs.setFloatUniform("stretch", 0f);
        rs.setFloatUniform("overshoot", 0f);
        rs.setFloatUniform("edgeSat", 0f);
        rs.setFloatUniform("innerDark", 0f);
        rs.setFloatUniform("tone", 1f);
        rs.setFloatUniform("refrH", 0f);
        rs.setFloatUniform("refrAmt", 0f);
        rs.setFloatUniform("chroma", 0f);
        return rs;
    }

    /**
     * Fondo para el shader con filtrado bilineal. Sin esto, dentro de un RuntimeShader la imagen
     * reducida se muestrea al píxel más cercano y el desenfoque se ve en cuadraditos.
     */
    public static BitmapShader linear(Bitmap b) {
        BitmapShader bs = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (android.os.Build.VERSION.SDK_INT >= 33) bs.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return bs;
    }

    static BitmapShader empty() {
        if (empty == null) {
            Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            empty = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        return empty;
    }

    // --- Fondo con el color ya hecho -------------------------------------------------------
    // vivid() solo depende del color, no de la posición: hacerlo una vez por píxel de la captura
    // (unos 150 000) en la CPU en vez de en cada píxel de pantalla en cada fotograma ahorra ~60
    // operaciones por píxel a la Mali-G52. Se hace en un hilo aparte; mientras, el shader lo sigue
    // calculando él (uniform baked = 0), así que nunca se ve un fotograma distinto.
    private static final java.util.Map<String, Bitmap> BAKED = new java.util.HashMap<>();
    private static final java.util.Set<String> BAKING = new java.util.HashSet<>();
    private static Bitmap bakedFrom;
    private static android.os.Handler bakeThread;

    /**
     * La captura {@code src} con vivid(sat, tone, darker, white) ya aplicado, o null si aún no está:
     * entonces se prepara y al acabar se llama a {@code ready} (desde otro hilo).
     */
    public static synchronized Bitmap baked(final Bitmap src, final float sat, final float tone,
                                            final float darker, final float white, final Runnable ready) {
        if (src != bakedFrom) {                             // captura nueva: las copias viejas no valen
            BAKED.clear();
            BAKING.clear();
            bakedFrom = src;
        }
        final String key = sat + "/" + tone + "/" + darker + "/" + white;
        Bitmap b = BAKED.get(key);
        if (b != null || BAKING.contains(key)) return b;    // si falló, se queda en BAKING: no se reintenta
        BAKING.add(key);
        if (bakeThread == null) bakeThread = com.icehome.up.Threads.start("HyperSpring-glassbake",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        bakeThread.post(new Runnable() {
            @Override
            public void run() {
                Bitmap out = null;
                try {
                    out = vividCopy(src, sat, tone, darker, white);
                } catch (Throwable ignored) {               // p. ej. la captura ya se liberó
                }
                boolean ok;
                synchronized (GlassShader.class) {
                    ok = out != null && bakedFrom == src;
                    if (ok) BAKED.put(key, out);
                }
                if (ok && ready != null) ready.run();
            }
        });
        return null;
    }

    /** Lo mismo que vivid() del shader, en la CPU. Si cambias uno, cambia el otro. */
    static Bitmap vividCopy(Bitmap src, float sat, float tone, float darker, float white) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        float[] c = new float[3];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            c[0] = ((p >> 16) & 255) / 255f;
            c[1] = ((p >> 8) & 255) / 255f;
            c[2] = (p & 255) / 255f;
            satKeep(c, sat);
            float l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
            float s = Math.max(0f, Math.min(1f, (l - 0.6f) / 0.4f));
            float dim = 1f - darker * s * s * (3f - 2f * s);
            int out = 0xFF000000;
            for (int k = 0; k < 3; k++) {
                float x = c[k] * dim * tone;
                if (x >= 0.8f) x = 0.8f + 0.2f * (1f - (float) Math.exp((0.8f - x) / 0.2f));
                x = x + (1f - x) * white;
                out |= Math.round(Math.max(0f, Math.min(1f, x)) * 255f) << (16 - 8 * k);
            }
            px[i] = out;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void satKeep(float[] c, float s) {
        float l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
        float hi = -1e9f, lo = 1e9f;
        for (int k = 0; k < 3; k++) {
            c[k] = l + (c[k] - l) * s;
            hi = Math.max(hi, c[k]);
            lo = Math.min(lo, c[k]);
        }
        float f = Math.min(1f, Math.min((1f - l) / Math.max(hi - l, 1e-4f), l / Math.max(l - lo, 1e-4f)));
        for (int k = 0; k < 3; k++) c[k] = l + (c[k] - l) * f;
    }

    private static final android.graphics.RectF OUT = new android.graphics.RectF(), IN = new android.graphics.RectF();

    /**
     * Pinta la pasada de luz (fill = 0) solo en un anillo de {@code reach} px junto al borde: más
     * adentro el shader devuelve transparente, y con drawRect la GPU lo calculaba igual en toda la
     * pieza. Hilo principal (usa rectángulos compartidos).
     */
    public static void drawRing(android.graphics.Canvas c, float w, float h, float r, float reach,
                                android.graphics.Paint p) {
        float in = reach + 1f;                              // 1 px de margen por el antialias del hueco
        if (in * 2f >= Math.min(w, h)) {
            c.drawRect(0, 0, w, h, p);
            return;
        }
        OUT.set(0, 0, w, h);
        IN.set(in, in, w - in, h - in);
        float ir = Math.max(0f, Math.min(r, Math.min(w, h) / 2f) - in);
        c.drawDoubleRoundRect(OUT, 0f, 0f, IN, ir, ir, p);
    }

    /**
     * Copia reducida y desenfocada: {@code pxPerSample} píxeles de pantalla por píxel del
     * resultado y un desenfoque de caja triple (≈ gaussiano) de {@code blurPx} píxeles de pantalla.
     * {@code screenPerSrc} = píxeles de pantalla que ocupa un píxel del original.
     */
    public static Bitmap shrinkBlur(Bitmap src, float screenPerSrc, float pxPerSample, float blurPx) {
        if (src.getConfig() == Bitmap.Config.HARDWARE) src = src.copy(Bitmap.Config.ARGB_8888, false);
        if (src == null) return null;
        float f = screenPerSrc / pxPerSample;
        int w = Math.max(8, Math.round(src.getWidth() * f)), h = Math.max(8, Math.round(src.getHeight() * f));
        Bitmap s = Bitmap.createScaledBitmap(src, w, h, true);
        if (s.getConfig() != Bitmap.Config.ARGB_8888) s = s.copy(Bitmap.Config.ARGB_8888, true);
        else if (!s.isMutable()) s = s.copy(Bitmap.Config.ARGB_8888, true);
        int r = Math.round(blurPx / pxPerSample / 1.7f);        // 3 cajas de radio r ≈ sigma 1.7·r
        if (r > 0) {
            int[] px = new int[w * h], tmp = new int[w * h];
            s.getPixels(px, 0, w, 0, 0, w, h);
            for (int pass = 0; pass < 3; pass++) {
                box(px, tmp, w, h, r, true);
                box(tmp, px, w, h, r, false);
            }
            s.setPixels(px, 0, w, 0, 0, w, h);
        }
        return s;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int ra = 0, ga = 0, ba = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[idx(l, clampI(i, len), w, horizontal)];
                ra += (c >> 16) & 255;
                ga += (c >> 8) & 255;
                ba += c & 255;
            }
            for (int i = 0; i < len; i++) {
                out[idx(l, i, w, horizontal)] = 0xFF000000 | (((ra + r) / div) << 16) | (((ga + r) / div) << 8) | ((ba + r) / div);   // redondeo, no truncado: no oscurece en cada pasada
                int cOut = in[idx(l, clampI(i - r, len), w, horizontal)];
                int cIn = in[idx(l, clampI(i + r + 1, len), w, horizontal)];
                ra += ((cIn >> 16) & 255) - ((cOut >> 16) & 255);
                ga += ((cIn >> 8) & 255) - ((cOut >> 8) & 255);
                ba += (cIn & 255) - (cOut & 255);
            }
        }
    }

    private static int idx(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    private static int clampI(int i, int len) {
        return i < 0 ? 0 : i >= len ? len - 1 : i;
    }

    private GlassShader() {
    }
}
