package me.hql.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Lexer {
    private static final Map<String, String> KEYWORDS = new HashMap<>();

    static {
        // Commands and keywords
        KEYWORDS.put("print", "print");
        KEYWORDS.put("input", "input");
        KEYWORDS.put("if", "if");
        KEYWORDS.put("else", "else");
        KEYWORDS.put("while", "while");
        KEYWORDS.put("for", "for");
        KEYWORDS.put("to", "to");
        KEYWORDS.put("in", "in");
        KEYWORDS.put("func", "func");
        KEYWORDS.put("return", "return");
        KEYWORDS.put("true", "true");
        KEYWORDS.put("false", "false");
        KEYWORDS.put("null", "null");
        KEYWORDS.put("not", "not");
        KEYWORDS.put("import", "import");
        KEYWORDS.put("use", "use");
        KEYWORDS.put("class", "class");
        KEYWORDS.put("extends", "extends");
        KEYWORDS.put("new", "new");
        KEYWORDS.put("var", "var");

        // Built-in functions
        KEYWORDS.put("call", "call");
        KEYWORDS.put("random", "random");
        KEYWORDS.put("type", "type");
        KEYWORDS.put("str", "str");
        KEYWORDS.put("num", "num");
        KEYWORDS.put("bool", "bool");

        // List functions
        KEYWORDS.put("add", "add");
        KEYWORDS.put("size", "size");
        KEYWORDS.put("len", "len");
        KEYWORDS.put("push", "push");
        KEYWORDS.put("pop", "pop");

        // hql.io library
        KEYWORDS.put("read", "read");
        KEYWORDS.put("write", "write");
        KEYWORDS.put("readA", "readA");
        KEYWORDS.put("writeA", "writeA");
        KEYWORDS.put("try", "try");
        KEYWORDS.put("catch", "catch");
        KEYWORDS.put("throw", "throw");
        KEYWORDS.put("create", "create");
        KEYWORDS.put("exists", "exists");
        KEYWORDS.put("delete", "delete");
        KEYWORDS.put("copy", "copy");
        KEYWORDS.put("move", "move");
        // --- CÁC HÀM HỆ THỐNG FILE (IO SIÊU CẤP) ---
        KEYWORDS.put("fileSize", "fileSize");
        KEYWORDS.put("getName", "getName");
        KEYWORDS.put("extension", "extension");
        KEYWORDS.put("isDir", "isDir");
        KEYWORDS.put("lastModified", "lastModified");
        KEYWORDS.put("list", "list");
        KEYWORDS.put("listFull", "listFull");
        KEYWORDS.put("find", "find");
        KEYWORDS.put("freeSpace", "freeSpace");
        KEYWORDS.put("totalSpace", "totalSpace");
        KEYWORDS.put("homeDir", "homeDir");
        KEYWORDS.put("workingDir", "workingDir");
        KEYWORDS.put("zip", "zip");
        KEYWORDS.put("unzip", "unzip");

        // hql.math library (SIÊU CẤP)
        KEYWORDS.put("sqrt", "sqrt");
        KEYWORDS.put("pow", "pow");
        KEYWORDS.put("round", "round");
        KEYWORDS.put("ceil", "ceil");
        KEYWORDS.put("floor", "floor");
        KEYWORDS.put("abs", "abs");
        KEYWORDS.put("sin", "sin");
        KEYWORDS.put("cos", "cos");
        KEYWORDS.put("tan", "tan");
        KEYWORDS.put("max", "max");
        KEYWORDS.put("min", "min");
        KEYWORDS.put("PI", "PI");
        KEYWORDS.put("E", "E");
        KEYWORDS.put("TAU", "TAU");
        KEYWORDS.put("INF", "INF");
        KEYWORDS.put("cbrt", "cbrt");
        KEYWORDS.put("trunc", "trunc");
        KEYWORDS.put("toRadians", "toRadians");
        KEYWORDS.put("hypot", "hypot");
        KEYWORDS.put("log", "log");
        KEYWORDS.put("log10", "log10");
        KEYWORDS.put("log2", "log2");
        KEYWORDS.put("factorial", "factorial");
        KEYWORDS.put("gcd", "gcd");
        KEYWORDS.put("lcm", "lcm");
        KEYWORDS.put("comb", "comb");
        KEYWORDS.put("clamp", "clamp");
        KEYWORDS.put("isqrt", "isqrt");
        KEYWORDS.put("fsum", "fsum");
        KEYWORDS.put("isclose", "isclose");
        KEYWORDS.put("isnan", "isnan");
        KEYWORDS.put("isinf", "isinf");
        KEYWORDS.put("NAN", "NAN");
        KEYWORDS.put("perm", "perm");
        KEYWORDS.put("degrees", "degrees");
        KEYWORDS.put("exp", "exp");

        // --- Thống kê ---
        KEYWORDS.put("mean", "mean");
        KEYWORDS.put("median", "median");
        KEYWORDS.put("variance", "variance");
        KEYWORDS.put("stddev", "stddev");
        KEYWORDS.put("correlation", "correlation");
        KEYWORDS.put("linearRegression", "linearRegression");

        // --- Ma trận ---
        KEYWORDS.put("matrixMultiply", "matrixMultiply");
        KEYWORDS.put("matrixTranspose", "matrixTranspose");
        KEYWORDS.put("matrixDeterminant", "matrixDeterminant");
        KEYWORDS.put("matrixInverse", "matrixInverse");

        // --- Chuyển đổi đơn vị (hql.math mở rộng) ---
        KEYWORDS.put("unitConvert", "unitConvert");
        KEYWORDS.put("listUnits", "listUnits");
        KEYWORDS.put("celsiusToFahrenheit", "celsiusToFahrenheit");
        KEYWORDS.put("fahrenheitToCelsius", "fahrenheitToCelsius");
        KEYWORDS.put("KM_TO_MILE", "KM_TO_MILE");
        KEYWORDS.put("MILE_TO_KM", "MILE_TO_KM");
        KEYWORDS.put("INCH_TO_CM", "INCH_TO_CM");
        KEYWORDS.put("CM_TO_INCH", "CM_TO_INCH");
        KEYWORDS.put("KG_TO_POUND", "KG_TO_POUND");
        KEYWORDS.put("POUND_TO_KG", "POUND_TO_KG");
        KEYWORDS.put("CELSIUS_OFFSET", "CELSIUS_OFFSET");
        KEYWORDS.put("LITRE_TO_GALLON", "LITRE_TO_GALLON");
        KEYWORDS.put("GALLON_TO_LITRE", "GALLON_TO_LITRE");
        KEYWORDS.put("METER_TO_FEET", "METER_TO_FEET");
        KEYWORDS.put("FEET_TO_METER", "FEET_TO_METER");

        // hql.net thư viện Networking
        KEYWORDS.put("httpGet", "httpGet");
        KEYWORDS.put("httpPost", "httpPost");
        KEYWORDS.put("httpQuery", "httpQuery");   // client for the HTTP QUERY method (server side: func doQuery() in a servlet; not a keyword)
        KEYWORDS.put("download", "download");
        KEYWORDS.put("isOnline", "isOnline");
        KEYWORDS.put("addHeader", "addHeader");
        KEYWORDS.put("setCookie", "setCookie");
        KEYWORDS.put("setTimeout", "setTimeout");
        KEYWORDS.put("setProxy", "setProxy");
        KEYWORDS.put("ignoreSSL", "ignoreSSL");
        KEYWORDS.put("getStatusCode", "getStatusCode");
        KEYWORDS.put("getContentType", "getContentType");
        KEYWORDS.put("getResponseHeader", "getResponseHeader");
        KEYWORDS.put("getIP", "getIP");
        KEYWORDS.put("getHost", "getHost");
        KEYWORDS.put("ping", "ping");
        KEYWORDS.put("checkPort", "checkPort");
        KEYWORDS.put("createServer", "createServer");

        // --- THƯ VIỆN HỆ THỐNG & PHẦN CỨNG (HQL.SYS) ---
        KEYWORDS.put("cpuModel", "cpuModel");
        KEYWORDS.put("cpuCores", "cpuCores");
        KEYWORDS.put("cpuThreads", "cpuThreads");
        KEYWORDS.put("cpuSpeed", "cpuSpeed");
        KEYWORDS.put("cpuVendor", "cpuVendor");
        KEYWORDS.put("machineModel", "machineModel");
        KEYWORDS.put("gpuModel", "gpuModel");
        KEYWORDS.put("gpuMemory", "gpuMemory");
        KEYWORDS.put("gpuDriver", "gpuDriver");
        KEYWORDS.put("resolution", "resolution");
        KEYWORDS.put("totalRam", "totalRam");
        KEYWORDS.put("freeRam", "freeRam");
        KEYWORDS.put("usedRam", "usedRam");
        KEYWORDS.put("ramSpeed", "ramSpeed");
        KEYWORDS.put("mainboard", "mainboard");
        KEYWORDS.put("biosVersion", "biosVersion");
        KEYWORDS.put("serialNumber", "serialNumber");
        KEYWORDS.put("osName", "osName");
        KEYWORDS.put("osArch", "osArch");
        KEYWORDS.put("userName", "userName");
        KEYWORDS.put("uptime", "uptime");
        KEYWORDS.put("exec", "exec");
        KEYWORDS.put("execRead", "execRead");
        KEYWORDS.put("exit", "exit");
        KEYWORDS.put("env", "env");
        KEYWORDS.put("gc", "gc");
        KEYWORDS.put("diskName", "diskName");
        KEYWORDS.put("diskStatus", "diskStatus");
        KEYWORDS.put("diskTemp", "diskTemp");
        KEYWORDS.put("diskWear", "diskWear");

        // --- THƯ VIỆN TIỆN ÍCH (HQL.UTIL) ---
        KEYWORDS.put("sort", "sort");
        KEYWORDS.put("reverse", "reverse");
        KEYWORDS.put("shuffle", "shuffle");
        KEYWORDS.put("unique", "unique");
        KEYWORDS.put("count", "count");
        KEYWORDS.put("md5", "md5");
        KEYWORDS.put("sha256", "sha256");
        KEYWORDS.put("base64Encode", "base64Encode");
        KEYWORDS.put("base64Decode", "base64Decode");
        KEYWORDS.put("uuid", "uuid");
        KEYWORDS.put("toJson", "toJson");
        KEYWORDS.put("fromJson", "fromJson");
        KEYWORDS.put("sleep", "sleep");
        KEYWORDS.put("range", "range");

        // --- THƯ VIỆN GIAO DIỆN (HQL.UI) ---
        KEYWORDS.put("alert", "alert");
        KEYWORDS.put("confirm", "confirm");
        KEYWORDS.put("prompt", "prompt");
        KEYWORDS.put("chooseFile", "chooseFile");
        KEYWORDS.put("chooseColor", "chooseColor");

        KEYWORDS.put("window", "window");
        KEYWORDS.put("setLayout", "setLayout");
        KEYWORDS.put("show", "show");
        KEYWORDS.put("hide", "hide");
        KEYWORDS.put("close", "close");

        KEYWORDS.put("button", "button");
        KEYWORDS.put("label", "label");
        KEYWORDS.put("textField", "textField");
        KEYWORDS.put("textArea", "textArea");
        KEYWORDS.put("image", "image");
        KEYWORDS.put("setStyle", "setStyle");
        KEYWORDS.put("getText", "getText");
        KEYWORDS.put("checkbox", "checkbox");
        KEYWORDS.put("radioButton", "radioButton");
        KEYWORDS.put("comboBox", "comboBox");
        KEYWORDS.put("onHover", "onHover");
        KEYWORDS.put("onKeyPress", "onKeyPress");
        KEYWORDS.put("trayInit", "trayInit");

        KEYWORDS.put("table", "table");
        KEYWORDS.put("setInterval", "setInterval");
        KEYWORDS.put("progressBar", "progressBar");

        // --- THƯ VIỆN BẢO MẬT (HQL.CRYPTO) ---
        KEYWORDS.put("encrypt", "encrypt");
        KEYWORDS.put("decrypt", "decrypt");
        KEYWORDS.put("generateKeys", "generateKeys");
        KEYWORDS.put("sign", "sign");
        KEYWORDS.put("verify", "verify");
        KEYWORDS.put("secureRandom", "secureRandom");
        KEYWORDS.put("pbkdf2", "pbkdf2");

        // --- THƯ VIỆN CƠ SỞ DỮ LIỆU (HQL.DATABASE) ---
        KEYWORDS.put("connect", "connect");
        KEYWORDS.put("closeDB", "closeDB");
        KEYWORDS.put("isOpen", "isOpen");
        KEYWORDS.put("query", "query");
        KEYWORDS.put("querySafe", "querySafe");
        KEYWORDS.put("execute", "execute");
        KEYWORDS.put("executeSafe", "executeSafe");
        KEYWORDS.put("identity", "identity");
        KEYWORDS.put("procedure", "procedure"); // Gọi Stored Procedure
        KEYWORDS.put("beginTransaction", "beginTransaction");
        KEYWORDS.put("commit", "commit");
        KEYWORDS.put("rollback", "rollback");
        KEYWORDS.put("tables", "tables");
        KEYWORDS.put("columns", "columns");
        KEYWORDS.put("exportCSV", "exportCSV"); // Bản nhẹ thay cho Excel
        KEYWORDS.put("fetchOne", "fetchOne");

        // --- THƯ VIỆN THỜI GIAN (HQL.TIME) ---
        KEYWORDS.put("now", "now");
        KEYWORDS.put("today", "today");
        KEYWORDS.put("currentTime", "currentTime");
        KEYWORDS.put("format", "format");
        KEYWORDS.put("parse", "parse");
        KEYWORDS.put("addMinutes", "addMinutes");
        KEYWORDS.put("addDays", "addDays");
        KEYWORDS.put("addMonths", "addMonths");
        KEYWORDS.put("addYears", "addYears");
        KEYWORDS.put("daysBetween", "daysBetween");
        KEYWORDS.put("getYear", "getYear");
        KEYWORDS.put("getMonth", "getMonth");
        KEYWORDS.put("getDay", "getDay");
        KEYWORDS.put("getWeekday", "getWeekday");
        KEYWORDS.put("age", "age");
        KEYWORDS.put("isWeekend", "isWeekend");
        KEYWORDS.put("isPast", "isPast");
        KEYWORDS.put("countdown", "countdown");

        // --- THƯ VIỆN REGEX (HQL.REGEX) ---
        KEYWORDS.put("matches", "matches");
        KEYWORDS.put("containsRegex", "containsRegex");
        KEYWORDS.put("findRegex", "findRegex");
        KEYWORDS.put("findAll", "findAll");
        KEYWORDS.put("replaceRegex", "replaceRegex");
        KEYWORDS.put("replaceFirst", "replaceFirst");
        KEYWORDS.put("splitRegex", "splitRegex");
        KEYWORDS.put("group", "group");
        KEYWORDS.put("groups", "groups");
        KEYWORDS.put("compile", "compile");
        KEYWORDS.put("quote", "quote");

        // --- THƯ VIỆN GAME (HQL.GAME) ---
        KEYWORDS.put("gameWindow", "gameWindow");
        KEYWORDS.put("setTitle", "setTitle");
        KEYWORDS.put("closeGame", "closeGame");

        KEYWORDS.put("clear", "clear");
        KEYWORDS.put("drawRect", "drawRect");
        KEYWORDS.put("fillRect", "fillRect");
        KEYWORDS.put("drawCircle", "drawCircle");
        KEYWORDS.put("fillCircle", "fillCircle");
        KEYWORDS.put("drawLine", "drawLine");
        KEYWORDS.put("drawText", "drawText");
        KEYWORDS.put("drawImage", "drawImage");

        KEYWORDS.put("sprite", "sprite");
        KEYWORDS.put("moveTo", "moveTo");
        KEYWORDS.put("moveBy", "moveBy");
        KEYWORDS.put("collide", "collide");
        KEYWORDS.put("removeSprite", "removeSprite");
        KEYWORDS.put("spritePos", "spritePos");

        KEYWORDS.put("isKeyDown", "isKeyDown");
        KEYWORDS.put("isKeyPressed", "isKeyPressed");
        KEYWORDS.put("mouseX", "mouseX");
        KEYWORDS.put("mouseY", "mouseY");
        KEYWORDS.put("isMouseDown", "isMouseDown");

        KEYWORDS.put("gameLoop", "gameLoop");
        KEYWORDS.put("playSound", "playSound");

        KEYWORDS.put("randomInt", "randomInt");
        KEYWORDS.put("distance", "distance");
        KEYWORDS.put("frameCount", "frameCount");

        KEYWORDS.put("rotateSprite", "rotateSprite");
        KEYWORDS.put("scaleSprite", "scaleSprite");
        KEYWORDS.put("stopSound", "stopSound");
        KEYWORDS.put("setVolume", "setVolume");
        KEYWORDS.put("setCamera", "setCamera");
        KEYWORDS.put("getTextWidth", "getTextWidth");

        // --- THƯ VIỆN TỰ ĐỘNG HÓA (HQL.PC) ---
        KEYWORDS.put("pos", "pos");
        KEYWORDS.put("scroll", "scroll");
        KEYWORDS.put("press", "press");
        KEYWORDS.put("release", "release");
        KEYWORDS.put("combo", "combo");
        KEYWORDS.put("findPixel", "findPixel");
        KEYWORDS.put("getClip", "getClip");
        KEYWORDS.put("setClip", "setClip");

        // --- THƯ VIỆN LOGGING (HQL.LOGGING) ---
        KEYWORDS.put("logInfo", "logInfo");
        KEYWORDS.put("logWarning", "logWarning");
        KEYWORDS.put("logError", "logError");
        KEYWORDS.put("logDebug", "logDebug");
        KEYWORDS.put("setLogLevel", "setLogLevel");
        KEYWORDS.put("setLogFile", "setLogFile");
        KEYWORDS.put("logReset", "logReset");

        // --- THƯ VIỆN XỬ LÝ ẢNH (HQL.IMAGE) ---
        KEYWORDS.put("loadImage", "loadImage");
        KEYWORDS.put("saveImage", "saveImage");
        KEYWORDS.put("resizeImage", "resizeImage");
        KEYWORDS.put("cropImage", "cropImage");
        KEYWORDS.put("rotateImage", "rotateImage");
        KEYWORDS.put("flipImage", "flipImage");
        KEYWORDS.put("grayscaleImage", "grayscaleImage");
        KEYWORDS.put("blurImage", "blurImage");
        KEYWORDS.put("invertImage", "invertImage");
        KEYWORDS.put("getPixelColor", "getPixelColor");
        KEYWORDS.put("setPixelColor", "setPixelColor");
        KEYWORDS.put("imageWidth", "imageWidth");
        KEYWORDS.put("imageHeight", "imageHeight");
        KEYWORDS.put("imageToSvg", "imageToSvg");
        KEYWORDS.put("svgToImage", "svgToImage");

        // --- THƯ VIỆN BIỂU ĐỒ (HQL.CHART) ---
        KEYWORDS.put("drawBarChart", "drawBarChart");
        KEYWORDS.put("drawLineChart", "drawLineChart");
        KEYWORDS.put("drawPieChart", "drawPieChart");
        KEYWORDS.put("drawScatterChart", "drawScatterChart");
        KEYWORDS.put("drawHistogram", "drawHistogram");
        KEYWORDS.put("setChartSize", "setChartSize");
        KEYWORDS.put("setChartColor", "setChartColor");
        KEYWORDS.put("saveChart", "saveChart");
        KEYWORDS.put("chartTitle", "chartTitle");

        // --- THƯ VIỆN CẤU HÌNH (HQL.CONFIG) ---
        KEYWORDS.put("loadConfig", "loadConfig");
        KEYWORDS.put("getConfig", "getConfig");
        KEYWORDS.put("setConfig", "setConfig");
        KEYWORDS.put("saveConfig", "saveConfig");
        KEYWORDS.put("listConfigKeys", "listConfigKeys");
        KEYWORDS.put("hasConfigKey", "hasConfigKey");
        KEYWORDS.put("removeConfigKey", "removeConfigKey");
        KEYWORDS.put("getSectionConfig", "getSectionConfig");
        KEYWORDS.put("setSectionConfig", "setSectionConfig");
        KEYWORDS.put("listSections", "listSections");
        KEYWORDS.put("listSectionKeys", "listSectionKeys");
        KEYWORDS.put("removeSection", "removeSection");

        // --- THƯ VIỆN CSV (HQL.CSV) ---
        KEYWORDS.put("readCsvFile", "readCsvFile");
        KEYWORDS.put("writeCsvFile", "writeCsvFile");
        KEYWORDS.put("readCsvRecords", "readCsvRecords");
        KEYWORDS.put("writeCsvRecords", "writeCsvRecords");
        KEYWORDS.put("readDelimitedFile", "readDelimitedFile");
        KEYWORDS.put("writeDelimitedFile", "writeDelimitedFile");

        KEYWORDS.put("csvRowCount", "csvRowCount");
        KEYWORDS.put("csvColumnCount", "csvColumnCount");
        KEYWORDS.put("getCsvCell", "getCsvCell");
        KEYWORDS.put("setCsvCell", "setCsvCell");
        KEYWORDS.put("getCsvField", "getCsvField");
        KEYWORDS.put("setCsvField", "setCsvField");

        KEYWORDS.put("addCsvRow", "addCsvRow");
        KEYWORDS.put("addCsvRecord", "addCsvRecord");
        KEYWORDS.put("removeCsvRow", "removeCsvRow");
        KEYWORDS.put("removeCsvRecord", "removeCsvRecord");

        KEYWORDS.put("sortCsvByColumn", "sortCsvByColumn");
        KEYWORDS.put("sortCsvRecordsByField", "sortCsvRecordsByField");
        KEYWORDS.put("filterCsvByColumn", "filterCsvByColumn");
        KEYWORDS.put("filterCsvRecordsByField", "filterCsvRecordsByField");

        KEYWORDS.put("csvRecordsToJson", "csvRecordsToJson");
        KEYWORDS.put("jsonToCsvRecords", "jsonToCsvRecords");

        // --- THƯ VIỆN XUẤT FILE CÓ ĐỊNH DẠNG (HQL.EXPORT): XLSX / DOCX / PDF / CSV ---
        KEYWORDS.put("exportXlsx", "exportXlsx");
        KEYWORDS.put("exportDocx", "exportDocx");
        KEYWORDS.put("exportPdf", "exportPdf");
        KEYWORDS.put("exportFile", "exportFile");
        KEYWORDS.put("webExport", "webExport");

        // --- THƯ VIỆN XML (HQL.XML) ---
        KEYWORDS.put("xmlReadFile", "xmlReadFile");
        KEYWORDS.put("xmlWriteFile", "xmlWriteFile");
        KEYWORDS.put("xmlParseString", "xmlParseString");
        KEYWORDS.put("xmlToString", "xmlToString");
        KEYWORDS.put("xmlCreateRoot", "xmlCreateRoot");

        KEYWORDS.put("xmlXpathQuery", "xmlXpathQuery");
        KEYWORDS.put("xmlXpathAttr", "xmlXpathAttr");
        KEYWORDS.put("xmlFindNodes", "xmlFindNodes");
        KEYWORDS.put("xmlGetNode", "xmlGetNode");
        KEYWORDS.put("xmlGetAttr", "xmlGetAttr");

        KEYWORDS.put("xmlToJson", "xmlToJson");
        KEYWORDS.put("jsonToXml", "jsonToXml");
        KEYWORDS.put("xmlToCsv", "xmlToCsv");
        KEYWORDS.put("csvToXml", "csvToXml");

        KEYWORDS.put("xmlSetNode", "xmlSetNode");
        KEYWORDS.put("xmlSetAttr", "xmlSetAttr");
        KEYWORDS.put("xmlAddChild", "xmlAddChild");
        KEYWORDS.put("xmlRemoveNode", "xmlRemoveNode");
        KEYWORDS.put("xmlRemoveAttr", "xmlRemoveAttr");
        KEYWORDS.put("xmlChildrenCount", "xmlChildrenCount");
        KEYWORDS.put("xmlHasNode", "xmlHasNode");

        // --- THƯ VIỆN NHỊ PHÂN (HQL.BINARY) ---
        KEYWORDS.put("binaryRead", "binaryRead");
        KEYWORDS.put("binaryWrite", "binaryWrite");
        KEYWORDS.put("binaryReadByte", "binaryReadByte");
        KEYWORDS.put("binaryWriteByte", "binaryWriteByte");
        KEYWORDS.put("binaryFileSize", "binaryFileSize");
        KEYWORDS.put("binaryPack", "binaryPack");
        KEYWORDS.put("binaryUnpack", "binaryUnpack");
        KEYWORDS.put("binaryPackToFile", "binaryPackToFile");
        KEYWORDS.put("binaryUnpackFromFile", "binaryUnpackFromFile");
        KEYWORDS.put("binaryHexDump", "binaryHexDump");
        KEYWORDS.put("binaryHexToBytes", "binaryHexToBytes");
        KEYWORDS.put("binaryBytesToHex", "binaryBytesToHex");
        KEYWORDS.put("binaryXor", "binaryXor");
        KEYWORDS.put("binaryCRC32", "binaryCRC32");
        KEYWORDS.put("binaryMD5", "binaryMD5");
        KEYWORDS.put("binarySHA256", "binarySHA256");

        // --- BỔ SUNG 10 HÀM MỚI CHO HQL.BINARY ---
        KEYWORDS.put("binaryReadBytes", "binaryReadBytes");
        KEYWORDS.put("binaryWriteBytes", "binaryWriteBytes");
        KEYWORDS.put("binaryCalcSize", "binaryCalcSize");
        KEYWORDS.put("binarySlice", "binarySlice");
        KEYWORDS.put("binaryConcat", "binaryConcat");
        KEYWORDS.put("binaryRepeat", "binaryRepeat");
        KEYWORDS.put("binaryToBase64", "binaryToBase64");
        KEYWORDS.put("binaryFromBase64", "binaryFromBase64");
        KEYWORDS.put("binaryReverse", "binaryReverse");
        KEYWORDS.put("binaryCompare", "binaryCompare");

        // --- THƯ VIỆN HQL.COLOR ---
        KEYWORDS.put("colorRgbToHex", "colorRgbToHex");
        KEYWORDS.put("colorHexToRgb", "colorHexToRgb");
        KEYWORDS.put("colorRgbToHsv", "colorRgbToHsv");
        KEYWORDS.put("colorHsvToRgb", "colorHsvToRgb");
        KEYWORDS.put("colorRgbToHsl", "colorRgbToHsl");
        KEYWORDS.put("colorHslToRgb", "colorHslToRgb");
        KEYWORDS.put("colorRgbToCmyk", "colorRgbToCmyk");
        KEYWORDS.put("colorCmykToRgb", "colorCmykToRgb");
        KEYWORDS.put("colorBlend", "colorBlend");
        KEYWORDS.put("colorMultiBlend", "colorMultiBlend");
        KEYWORDS.put("colorGradient", "colorGradient");
        KEYWORDS.put("colorMultiGradient", "colorMultiGradient");
        KEYWORDS.put("colorRandomGradient", "colorRandomGradient");
        KEYWORDS.put("colorLighten", "colorLighten");
        KEYWORDS.put("colorDarken", "colorDarken");
        KEYWORDS.put("colorSaturate", "colorSaturate");
        KEYWORDS.put("colorDesaturate", "colorDesaturate");
        KEYWORDS.put("colorAdjustHue", "colorAdjustHue");
        KEYWORDS.put("colorLuminance", "colorLuminance");
        KEYWORDS.put("colorContrastRatio", "colorContrastRatio");
        KEYWORDS.put("colorIsLight", "colorIsLight");
        KEYWORDS.put("colorIsDark", "colorIsDark");
        KEYWORDS.put("colorDistance", "colorDistance");
        KEYWORDS.put("colorSimilarity", "colorSimilarity");
        KEYWORDS.put("colorToGreyscale", "colorToGreyscale");
        KEYWORDS.put("colorRandomHex", "colorRandomHex");
        KEYWORDS.put("colorRandomRgb", "colorRandomRgb");
        KEYWORDS.put("colorRandomPalette", "colorRandomPalette");
        KEYWORDS.put("colorNameToRgb", "colorNameToRgb");
        KEYWORDS.put("colorRgbToName", "colorRgbToName");
        KEYWORDS.put("colorWithOpacity", "colorWithOpacity");
        KEYWORDS.put("colorExtractPalette", "colorExtractPalette");

        // --- THƯ VIỆN SOCKET & MẠNG SIÊU CẤP (HQL.SOCKET) ---
        KEYWORDS.put("socketTcpConnect", "socketTcpConnect");
        KEYWORDS.put("socketTcpSend", "socketTcpSend");
        KEYWORDS.put("socketTcpReceive", "socketTcpReceive");
        KEYWORDS.put("socketTcpClose", "socketTcpClose");
        KEYWORDS.put("socketTcpStatus", "socketTcpStatus");
        KEYWORDS.put("socketTcpListen", "socketTcpListen");
        KEYWORDS.put("socketTcpAccept", "socketTcpAccept");
        KEYWORDS.put("socketTcpSendTo", "socketTcpSendTo");
        KEYWORDS.put("socketTcpBroadcast", "socketTcpBroadcast");
        KEYWORDS.put("socketTcpDisconnectClient", "socketTcpDisconnectClient");

        KEYWORDS.put("socketUdpBind", "socketUdpBind");
        KEYWORDS.put("socketUdpSend", "socketUdpSend");
        KEYWORDS.put("socketUdpReceive", "socketUdpReceive");
        KEYWORDS.put("socketUdpClose", "socketUdpClose");
        KEYWORDS.put("socketUdpBroadcast", "socketUdpBroadcast");
        KEYWORDS.put("socketUdpMulticastJoin", "socketUdpMulticastJoin");
        KEYWORDS.put("socketUdpMulticastLeave", "socketUdpMulticastLeave");

        KEYWORDS.put("socketSslTcpConnect", "socketSslTcpConnect");
        KEYWORDS.put("socketSslTcpListen", "socketSslTcpListen");
        KEYWORDS.put("socketSslTcpAccept", "socketSslTcpAccept");
        KEYWORDS.put("socketSslTcpSend", "socketSslTcpSend");

        KEYWORDS.put("socketSetTimeout", "socketSetTimeout");
        KEYWORDS.put("socketSetTcpNoDelay", "socketSetTcpNoDelay");
        KEYWORDS.put("socketSetKeepAlive", "socketSetKeepAlive");

        KEYWORDS.put("socketResolveHost", "socketResolveHost");
        KEYWORDS.put("socketGetLocalIp", "socketGetLocalIp");
        KEYWORDS.put("socketGetRemoteIp", "socketGetRemoteIp");
        KEYWORDS.put("socketGetRemotePort", "socketGetRemotePort");
        KEYWORDS.put("socketListInterfaces", "socketListInterfaces");
        KEYWORDS.put("socketGetHostName", "socketGetHostName");

        KEYWORDS.put("socketRawPing", "socketRawPing");
        KEYWORDS.put("socketRawSend", "socketRawSend");

        KEYWORDS.put("socketWebSocketConnect", "socketWebSocketConnect");
        KEYWORDS.put("socketWebSocketSend", "socketWebSocketSend");
        KEYWORDS.put("socketWebSocketReceive", "socketWebSocketReceive");

        // --- CÁC HÀM MỞ RỘNG (110%) CHO HQL.SOCKET ---
        KEYWORDS.put("socketSendFile", "socketSendFile");
        KEYWORDS.put("socketReceiveFile", "socketReceiveFile");
        KEYWORDS.put("socketEnableEncryption", "socketEnableEncryption");
        KEYWORDS.put("socketDisableEncryption", "socketDisableEncryption");
        KEYWORDS.put("socketSetNonBlocking", "socketSetNonBlocking");
        KEYWORDS.put("socketWaitData", "socketWaitData");
        KEYWORDS.put("socketSetOption", "socketSetOption");

        // --- THƯ VIỆN LẬP LỊCH TÁC VỤ (HQL.SCHEDULE) ---
        KEYWORDS.put("taskRunOnce", "taskRunOnce");
        KEYWORDS.put("taskRunAt", "taskRunAt");
        KEYWORDS.put("taskRepeat", "taskRepeat");
        KEYWORDS.put("taskRepeatFixedDelay", "taskRepeatFixedDelay");
        KEYWORDS.put("taskCancel", "taskCancel");
        KEYWORDS.put("taskCancelAll", "taskCancelAll");
        KEYWORDS.put("taskExists", "taskExists");

        KEYWORDS.put("taskCron", "taskCron");
        KEYWORDS.put("taskCronAt", "taskCronAt");
        KEYWORDS.put("taskCronUntil", "taskCronUntil");
        KEYWORDS.put("cronValidate", "cronValidate");
        KEYWORDS.put("cronNextRun", "cronNextRun");

        KEYWORDS.put("taskPause", "taskPause");
        KEYWORDS.put("taskResume", "taskResume");
        KEYWORDS.put("taskFireNow", "taskFireNow");
        KEYWORDS.put("taskReschedule", "taskReschedule");
        KEYWORDS.put("taskSetMaxRuns", "taskSetMaxRuns");

        KEYWORDS.put("taskChain", "taskChain");
        KEYWORDS.put("taskDepend", "taskDepend");
        KEYWORDS.put("taskOnSuccess", "taskOnSuccess");
        KEYWORDS.put("taskOnFailure", "taskOnFailure");

        KEYWORDS.put("taskWeekly", "taskWeekly");
        KEYWORDS.put("taskMonthly", "taskMonthly");
        KEYWORDS.put("taskLastDayOfMonth", "taskLastDayOfMonth");
        KEYWORDS.put("taskHoliday", "taskHoliday");

        KEYWORDS.put("taskSaveState", "taskSaveState");
        KEYWORDS.put("taskLoadState", "taskLoadState");
        KEYWORDS.put("taskEnablePersistence", "taskEnablePersistence");
        KEYWORDS.put("taskGetRuns", "taskGetRuns");
        KEYWORDS.put("taskResetStats", "taskResetStats");

        KEYWORDS.put("taskRunParallel", "taskRunParallel");
        KEYWORDS.put("taskAcquireLock", "taskAcquireLock");
        KEYWORDS.put("taskReleaseLock", "taskReleaseLock");
        KEYWORDS.put("taskWaitFor", "taskWaitFor");
        KEYWORDS.put("taskBroadcastEvent", "taskBroadcastEvent");

        KEYWORDS.put("taskList", "taskList");
        KEYWORDS.put("taskDetails", "taskDetails");
        KEYWORDS.put("taskCount", "taskCount");
        KEYWORDS.put("taskActiveCount", "taskActiveCount");
        KEYWORDS.put("taskPauseAll", "taskPauseAll");
        KEYWORDS.put("taskResumeAll", "taskResumeAll");
        KEYWORDS.put("taskShutdown", "taskShutdown");

        // --- THƯ VIỆN HQL.MAIL (gửi email) ---
        KEYWORDS.put("mailConfig", "mailConfig");
        KEYWORDS.put("mailConfigEnv", "mailConfigEnv");
        KEYWORDS.put("mailWarmUp", "mailWarmUp");
        KEYWORDS.put("mailSenderName", "mailSenderName");
        KEYWORDS.put("mailIsConfigured", "mailIsConfigured");
        KEYWORDS.put("mailError", "mailError");
        KEYWORDS.put("mailAttach", "mailAttach");
        KEYWORDS.put("mailClearAttach", "mailClearAttach");
        KEYWORDS.put("mailOtp", "mailOtp");
        KEYWORDS.put("mailPassword", "mailPassword");
        KEYWORDS.put("mailIsValid", "mailIsValid");
        KEYWORDS.put("mailSend", "mailSend");
        KEYWORDS.put("mailSendText", "mailSendText");
        KEYWORDS.put("mailSendAsync", "mailSendAsync");
        KEYWORDS.put("mailSendAsyncText", "mailSendAsyncText");

        // --- THƯ VIỆN HQL.ENV (tệp cấu hình .henv) ---
        KEYWORDS.put("envLoad", "envLoad");
        KEYWORDS.put("envGet", "envGet");
        KEYWORDS.put("envHas", "envHas");
        KEYWORDS.put("envSet", "envSet");
        KEYWORDS.put("envInt", "envInt");
        KEYWORDS.put("envBool", "envBool");
        KEYWORDS.put("envAll", "envAll");
        KEYWORDS.put("envFiles", "envFiles");
        KEYWORDS.put("envRequire", "envRequire");

        // --- HQL.DATABASE: bể kết nối + nối theo .henv + chèn hàng loạt ---
        KEYWORDS.put("connectEnv", "connectEnv");
        KEYWORDS.put("dbPoolSize", "dbPoolSize");
        KEYWORDS.put("dbPoolStats", "dbPoolStats");
        KEYWORDS.put("executeBatch", "executeBatch");

        // --- HQL.DATABASE: NoSQL (MongoDB) — xử lý trong HqlDbPool.NoSql ---
        KEYWORDS.put("nosqlConnect", "nosqlConnect");
        KEYWORDS.put("nosqlConnectEnv", "nosqlConnectEnv");
        KEYWORDS.put("nosqlClose", "nosqlClose");
        KEYWORDS.put("nosqlIsOpen", "nosqlIsOpen");
        KEYWORDS.put("nosqlError", "nosqlError");
        KEYWORDS.put("nosqlPing", "nosqlPing");
        KEYWORDS.put("nosqlDatabase", "nosqlDatabase");
        KEYWORDS.put("nosqlDatabases", "nosqlDatabases");
        KEYWORDS.put("nosqlCollections", "nosqlCollections");
        KEYWORDS.put("nosqlInsert", "nosqlInsert");
        KEYWORDS.put("nosqlInsertMany", "nosqlInsertMany");
        KEYWORDS.put("nosqlFind", "nosqlFind");
        KEYWORDS.put("nosqlFindOne", "nosqlFindOne");
        KEYWORDS.put("nosqlUpdate", "nosqlUpdate");
        KEYWORDS.put("nosqlUpdateMany", "nosqlUpdateMany");
        KEYWORDS.put("nosqlDelete", "nosqlDelete");
        KEYWORDS.put("nosqlDeleteMany", "nosqlDeleteMany");
        KEYWORDS.put("nosqlCount", "nosqlCount");
        KEYWORDS.put("nosqlAggregate", "nosqlAggregate");
        KEYWORDS.put("nosqlIndex", "nosqlIndex");
        KEYWORDS.put("nosqlDrop", "nosqlDrop");
        KEYWORDS.put("nosqlCommand", "nosqlCommand");
        KEYWORDS.put("nosqlDate", "nosqlDate");
        KEYWORDS.put("nosqlDoc", "nosqlDoc");

        // --- THƯ VIỆN WEB / HQLSP (HQL.WEB) ---
        KEYWORDS.put("printWeb", "printWeb");
        KEYWORDS.put("webStart", "webStart");
        KEYWORDS.put("webStop", "webStop");
        KEYWORDS.put("webWait", "webWait");
        KEYWORDS.put("webRoot", "webRoot");
        KEYWORDS.put("webRoute", "webRoute");
        KEYWORDS.put("webServlet", "webServlet");
        KEYWORDS.put("webExportTomcat", "webExportTomcat");
        KEYWORDS.put("webContext", "webContext");

        KEYWORDS.put("param", "param");
        KEYWORDS.put("params", "params");
        KEYWORDS.put("webMethod", "webMethod");
        KEYWORDS.put("webPath", "webPath");
        KEYWORDS.put("webQuery", "webQuery");
        KEYWORDS.put("webBody", "webBody");
        KEYWORDS.put("webIP", "webIP");
        KEYWORDS.put("reqHeader", "reqHeader");

        KEYWORDS.put("webStatus", "webStatus");
        KEYWORDS.put("webType", "webType");
        KEYWORDS.put("resHeader", "resHeader");
        KEYWORDS.put("webRedirect", "webRedirect");
        KEYWORDS.put("webInclude", "webInclude");
        KEYWORDS.put("webRender", "webRender");
        KEYWORDS.put("webJson", "webJson");

        KEYWORDS.put("sessionSet", "sessionSet");
        KEYWORDS.put("sessionGet", "sessionGet");
        KEYWORDS.put("sessionDel", "sessionDel");
        KEYWORDS.put("sessionId", "sessionId");
        KEYWORDS.put("sessionKill", "sessionKill");
        KEYWORDS.put("cookieSet", "cookieSet");
        KEYWORDS.put("cookieGet", "cookieGet");

        KEYWORDS.put("htmlEscape", "htmlEscape");
        KEYWORDS.put("urlEncode", "urlEncode");
        KEYWORDS.put("urlDecode", "urlDecode");
        KEYWORDS.put("webMime", "webMime");

        // --- BỔ SUNG HQL.DATABASE ---
        KEYWORDS.put("dbError", "dbError");
        KEYWORDS.put("queryValue", "queryValue");
        KEYWORDS.put("unitCompare", "unitCompare");

        // String methods
        KEYWORDS.put("trim", "trim");
        KEYWORDS.put("strip", "strip");
        KEYWORDS.put("lstrip", "lstrip");
        KEYWORDS.put("rstrip", "rstrip");
        KEYWORDS.put("split", "split");
        KEYWORDS.put("substring", "substring");
        KEYWORDS.put("toLowerCase", "toLowerCase");
        KEYWORDS.put("toUpperCase", "toUpperCase");
        KEYWORDS.put("indexOf", "indexOf");
        KEYWORDS.put("lastIndexOf", "lastIndexOf");
        KEYWORDS.put("startsWith", "startsWith");
        KEYWORDS.put("endsWith", "endsWith");
        KEYWORDS.put("replace", "replace");
        KEYWORDS.put("insert", "insert");
        KEYWORDS.put("removeAt", "removeAt");
        KEYWORDS.put("join", "join");

        // --- TỪ KHÓA ĐIỀU KHIỂN LUỒNG ---
        KEYWORDS.put("switch", "switch");
        KEYWORDS.put("case", "case");
        KEYWORDS.put("default", "default");
        KEYWORDS.put("break", "break");
        KEYWORDS.put("continue", "continue");

        // Các từ khóa mới
        KEYWORDS.put("undefined", "undefined");
        KEYWORDS.put("finally", "finally");
        KEYWORDS.put("map", "map");
        KEYWORDS.put("filter", "filter");
        KEYWORDS.put("reduce", "reduce");
    }

    /**
     * Dịch ngược escape trong chuỗi "..." bằng cách quét đúng 1 lượt.
     * Cách cũ dùng replace() nối tiếp làm hỏng các chuỗi kiểu "\\n" (backslash + chữ n) của JS/Regex.
     */
    private static String unescape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch != '\\' || i == raw.length() - 1) { sb.append(ch); continue; }
            char next = raw.charAt(++i);
            switch (next) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case '0': sb.append('\0'); break;
                case '"': sb.append('"'); break;
                case '\'': sb.append('\''); break;
                case '\\': sb.append('\\'); break;
                case 'u':
                    if (i + 4 < raw.length()) {
                        try {
                            sb.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) { sb.append("\\u"); }
                    } else sb.append("\\u");
                    break;
                default: sb.append('\\').append(next); // Giữ nguyên các escape lạ (vd: \d của Regex)
            }
        }
        return sb.toString();
    }

    /**
     * Cắt gọt chuỗi """...""" theo chuẩn Text Block của Java:
     * bỏ dòng trắng đầu tiên và trừ đi phần thụt lề chung của cả khối.
     */
    private static String stripTextBlock(String raw) {
        // 1. Bỏ phần còn lại của dòng mở ngoặc (thường là dấu xuống dòng ngay sau """)
        int firstNewLine = raw.indexOf('\n');
        if (firstNewLine != -1 && raw.substring(0, firstNewLine).isBlank()) {
            raw = raw.substring(firstNewLine + 1);
        }
        raw = raw.replace("\r\n", "\n");

        String[] lines = raw.split("\n", -1);

        // 2. Tìm mức thụt lề nhỏ nhất trong các dòng có nội dung
        int minIndent = Integer.MAX_VALUE;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() && i != lines.length - 1) continue; // Dòng trắng không tính (trừ dòng đóng)
            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) indent++;
            if (i == lines.length - 1 && line.isBlank()) indent = line.length(); // Dòng chứa """ đóng
            minIndent = Math.min(minIndent, indent);
        }
        if (minIndent == Integer.MAX_VALUE) minIndent = 0;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String cut = line.length() >= minIndent ? line.substring(minIndent) : line.strip();
            // Dòng cuối chỉ chứa khoảng trắng (do dấu """ đóng nằm dòng riêng) thì bỏ luôn
            if (i == lines.length - 1 && cut.isBlank()) break;
            if (i > 0) sb.append('\n');
            sb.append(stripTrailing(cut));
        }
        return unescapeTextBlock(sb.toString());
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t' || s.charAt(end - 1) == '\r')) end--;
        return s.substring(0, end);
    }

    /** Trong Text Block chỉ dịch \" và \\ — giữ nguyên \n, \d... để HTML/JS/CSS không bị hỏng. */
    private static String unescapeTextBlock(String raw) {
        return raw.replace("\\\"\"\"", "\"\"\"");
    }

    public static List<Token> tokenize(String code, String fileName) {
        List<Token> tokens = new ArrayList<>();
        int currentLine = 1;

        while (!code.isEmpty()) {
            if (code.startsWith("//") || code.startsWith("#")) {
                int endOfLine = code.indexOf('\n');
                if (endOfLine != -1) {
                    code = code.substring(endOfLine + 1);
                    tokens.add(new Token("END", ";", fileName, currentLine));
                    currentLine++;
                } else {
                    code = "";
                }
                continue;
            }

            // 2. THÊM MỚI: Chặn comment nhiều dòng (/* ... */)
            if (code.startsWith("/*")) {
                int endOfComment = code.indexOf("*/");
                if (endOfComment != -1) {
                    // Đếm số dòng bị thụt để báo lỗi đúng số dòng
                    String commentBody = code.substring(0, endOfComment + 2);
                    for (char ch : commentBody.toCharArray()) {
                        if (ch == '\n') currentLine++;
                    }
                    code = code.substring(endOfComment + 2); // Bỏ qua toàn bộ khối comment
                } else {
                    System.out.println("Lỗi: Chưa đóng comment */ | Error: Comments are not closed in line " + currentLine);
                    break;
                }
                continue;
            }

            if (code.startsWith("\n")) {
                tokens.add(new Token("END", ";", fileName, currentLine));
                currentLine++;
                code = code.substring(1);
                continue;
            }

            if (code.startsWith(" ") || code.startsWith("\r") || code.startsWith("\t")) {
                code = code.substring(1);
                continue;
            }

            if (code.startsWith("&&") || code.startsWith("||") || code.startsWith("==") || code.startsWith("!=") || code.startsWith(">=") || code.startsWith("<=") || code.startsWith("..") || code.startsWith("++") || code.startsWith("--") || code.startsWith("+=") || code.startsWith("-=") || code.startsWith("*=") || code.startsWith("/=")) {
                String op = code.substring(0, 2);
                String type = switch(op) {
                    case "&&", "||" -> "LOGIC";
                    case "==", "!=", ">=", "<=" -> "COMPARE";
                    case ".." -> "RANGE";
                    case "+=", "-=", "*=", "/=" -> "ASSIGN_OP";
                    case "++", "--" -> "INC_DEC";
                    default -> "OPERATOR";
                };
                tokens.add(new Token(type, op, fileName, currentLine));
                code = code.substring(2);
                continue;
            }

            if (code.startsWith("...")) {
                tokens.add(new Token("SPREAD", "...", fileName, currentLine));
                code = code.substring(3);
                continue;
            }

            char c = code.charAt(0);
            if ("!><=()[]{},;.*+-/%?:^".indexOf(c) != -1) {
                String op = String.valueOf(c);
                String type = switch(op) {
                    case "?" -> "TERNARY_Q";
                    case ":" -> "COLON";
                    case "!", "not" -> "NOT";
                    case ">", "<" -> "COMPARE";
                    case "=" -> "ASSIGN";
                    case "(", ")" -> op.equals("(") ? "L_PAREN" : "R_PAREN";
                    case "{", "}" -> op.equals("{") ? "L_BRACE" : "R_BRACE";
                    case "[", "]" -> op.equals("[") ? "L_BRACKET" : "R_BRACKET";
                    case "," -> "COMMA";
                    case ";" -> "END";
                    case "." -> "DOT";
                    default -> "OPERATOR";
                };
                tokens.add(new Token(type, op, fileName, currentLine));
                code = code.substring(1);
                continue;
            }

            // --- XỬ LÝ CHUỖI NHIỀU DÒNG """ ... """ (TEXT BLOCK - GIỐNG JAVA) ---
            // Dùng để nhúng nguyên khối HTML vào printWeb("""...""") mà không cần escape.
            if (code.startsWith("\"\"\"")) {
                int endBlock = code.indexOf("\"\"\"", 3);
                if (endBlock == -1) {
                    throw new RuntimeException("Lỗi Cú Pháp: Chuỗi \"\"\" chưa được đóng tại dòng " + currentLine);
                }
                String raw = code.substring(3, endBlock);

                // Đếm dòng trước khi cắt gọt để báo lỗi đúng vị trí
                // So dong cua token phai la cho MO khoi. Lay dong dong thi mot khoi
                // HTML 200 dong se bao loi o tan cuoi khoi, xa han cau printWeb(...)
                // dang hong - doc bao loi xong van khong biet tim o dau.
                int blockOpenLine = currentLine;
                for (char ch : code.substring(0, endBlock + 3).toCharArray()) {
                    if (ch == '\n') currentLine++;
                }

                tokens.add(new Token("STRING", stripTextBlock(raw), fileName, blockOpenLine));
                code = code.substring(endBlock + 3);
                continue;
            }

            // --- XỬ LÝ CHUỖI THÔNG MINH (HỖ TRỢ ESCAPE CHARACTER) ---
            if (c == '"') {
                int endQuote = 1;
                boolean isEscaped = false;

                // Duyệt từng ký tự để tìm dấu nháy kép đóng thực sự
                while (endQuote < code.length()) {
                    char ch = code.charAt(endQuote);
                    if (ch == '"' && !isEscaped) break; // Tìm thấy nháy kép đóng hợp lệ

                    // Cập nhật trạng thái escape: Nếu gặp '\', ký tự tiếp theo sẽ bị bỏ qua luật mặc định
                    isEscaped = (ch == '\\' && !isEscaped);
                    endQuote++;
                }

                if (endQuote < code.length()) {
                    // Dịch ngược các ký tự escape về dạng chuẩn (quét 1 lượt, không bị dịch chồng)
                    String rawText = code.substring(1, endQuote);   // nguyen van nam trong file
                    String strValue = unescape(rawText);
                    tokens.add(new Token("STRING", strValue, fileName, currentLine));

                    // Cập nhật số dòng nếu trong chuỗi có dấu xuống dòng
                    /*
                     * DEM XUONG DONG TREN BAN THO, KHONG PHAI BAN DA DICH ESCAPE.
                     *
                     * Ban dau dem tren strValue - tuc la SAU khi unescape da bien hai
                     * ky tu  \ n  thanh MOT ky tu xuong dong that. Viet
                     * print("a\nb") gon trong MOT dong ma bo dem van cong them 1,
                     * nen tu do tro di moi so dong bao loi deu lon hon thuc te, cang ve
                     * cuoi file cang lech. MyApp/main.hql lech +7 o khoang dong
                     * 2800: mui ten >> chi vao dong chu thich chu khong phai cau lenh
                     * dang hong. Chi dau xuong dong THAT trong ma nguon moi duoc tinh.
                     */
                    for (int rawIdx = 0; rawIdx < rawText.length(); rawIdx++) {
                        if (rawText.charAt(rawIdx) == '\n') currentLine++;
                    }

                    code = code.substring(endQuote + 1);
                    continue;
                } else {
                    System.out.println("Lỗi cú pháp: Chuỗi chưa được đóng ngoặc kép tại dòng " + currentLine);
                    break;
                }
            }

            // --- XỬ LÝ CHỮ VÀ TỪ KHÓA (CÓ TỰ ĐỘNG NHẬN DIỆN HÀM/BIẾN) ---
            Matcher wordMatcher = Pattern.compile("^@?[\\p{L}_][\\p{L}0-9_]*").matcher(code);

            if (wordMatcher.find()) {
                String word = wordMatcher.group();
                String nextCode = code.substring(word.length()).trim(); // Lấy phần code phía sau để dò xét ngữ cảnh

                // Danh sách các từ khóa CẤU TRÚC lõi (Tuyệt đối không được làm biến)
                java.util.List<String> coreKeywords = java.util.Arrays.asList(
                        "if", "else", "while", "for", "to", "in", "func", "return",
                        "true", "false", "null", "not", "import", "use", "class", "extends", "new", "var", "const"
                );

                if (word.equals("and")) {
                    tokens.add(new Token("LOGIC", "&&", fileName, currentLine));
                } else if (word.equals("or")) {
                    tokens.add(new Token("LOGIC", "||", fileName, currentLine));
                } else if (coreKeywords.contains(word)) {
                    // Khóa cứng các lệnh cấu trúc
                    tokens.add(new Token("KEYWORD", word, fileName, currentLine));
                } else if (KEYWORDS.containsKey(word)) {
                    // --- CƠ CHẾ TỰ ĐỘNG NHẬN DIỆN THÔNG MINH ---
                    // Nếu từ khóa có dấu ngoặc đơn phía sau -> Nó là Lệnh/Hàm
                    if (nextCode.startsWith("(")) {
                        tokens.add(new Token("KEYWORD", KEYWORDS.get(word), fileName, currentLine));
                    } else {
                        // Nếu không có ngoặc, thả nó ra làm Biến bình thường (IDENTIFIER)
                        tokens.add(new Token("IDENTIFIER", word, fileName, currentLine));
                    }
                } else {
                    tokens.add(new Token("IDENTIFIER", word, fileName, currentLine));
                }
                code = code.substring(word.length());
                continue;
            }

            Matcher numMatcher = Pattern.compile("^[0-9]+(\\.[0-9]+)?").matcher(code);
            if (numMatcher.find()) {
                String num = numMatcher.group();
                tokens.add(new Token("NUMBER", num, fileName, currentLine));
                code = code.substring(num.length());
                continue;
            }
            throw new RuntimeException("Lỗi Cú Pháp (Lexer): Không hiểu ký tự lạ '" + code.charAt(0) + "' tại dòng " + currentLine);
        }
        return tokens;
    }
}