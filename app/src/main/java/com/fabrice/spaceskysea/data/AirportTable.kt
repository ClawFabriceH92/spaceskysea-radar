package com.fabrice.spaceskysea.data

/**
 * Codes aéroports OACI (4 lettres, renvoyés par OpenSky) → « IATA Ville »
 * lisible. Couvre la France (métropole + outre-mer), l'Europe et les grands
 * hubs mondiaux ; un code inconnu est affiché tel quel.
 */
object AirportTable {

    private val TABLE: Map<String, String> = mapOf(
        // France métropolitaine
        "LFPG" to "CDG Paris", "LFPO" to "ORY Paris-Orly", "LFPB" to "LBG Le Bourget",
        "LFOB" to "BVA Beauvais", "LFBO" to "TLS Toulouse", "LFML" to "MRS Marseille",
        "LFLL" to "LYS Lyon", "LFMN" to "NCE Nice", "LFBD" to "BOD Bordeaux",
        "LFRS" to "NTE Nantes", "LFST" to "SXB Strasbourg", "LFQQ" to "LIL Lille",
        "LFLC" to "CFE Clermont-Fd", "LFRB" to "BES Brest", "LFRN" to "RNS Rennes",
        "LFMT" to "MPL Montpellier", "LFKJ" to "AJA Ajaccio", "LFKB" to "BIA Bastia",
        "LFKF" to "FSC Figari", "LFKC" to "CLY Calvi", "LFMP" to "PGF Perpignan",
        "LFBP" to "PUF Pau", "LFBZ" to "BIQ Biarritz", "LFTW" to "FNI Nîmes",
        "LFTH" to "TLN Toulon", "LFSB" to "BSL Bâle-Mulhouse", "LFRQ" to "UIP Quimper",
        "LFRD" to "DNR Dinard", "LFRH" to "LRT Lorient", "LFBH" to "LRH La Rochelle",
        "LFBL" to "LIG Limoges", "LFLB" to "CMF Chambéry", "LFLP" to "NCY Annecy",
        "LFLS" to "GNB Grenoble", "LFSD" to "DIJ Dijon", "LFJL" to "ETZ Metz-Nancy",
        "LFOT" to "TUF Tours", "LFRK" to "CFR Caen", "LFOP" to "URO Rouen",
        "LFAQ" to "BYF Albert", "LFGA" to "CMR Colmar", "LFMD" to "CEQ Cannes",
        // Outre-mer
        "TFFR" to "PTP Pointe-à-Pitre", "TFFF" to "FDF Fort-de-France",
        "SOCA" to "CAY Cayenne", "FMEE" to "RUN La Réunion", "FMCZ" to "DZA Mayotte",
        "NWWW" to "NOU Nouméa", "NTAA" to "PPT Papeete", "LFVP" to "FSP St-Pierre",
        // Europe
        "EGLL" to "LHR Londres", "EGKK" to "LGW Londres-Gatwick", "EGSS" to "STN Londres-Stansted",
        "EGGW" to "LTN Londres-Luton", "EGLC" to "LCY Londres-City", "EGCC" to "MAN Manchester",
        "EGPH" to "EDI Édimbourg", "EGBB" to "BHX Birmingham", "EGGD" to "BRS Bristol",
        "EIDW" to "DUB Dublin", "EHAM" to "AMS Amsterdam", "EBBR" to "BRU Bruxelles",
        "EBCI" to "CRL Charleroi", "ELLX" to "LUX Luxembourg", "EDDF" to "FRA Francfort",
        "EDDM" to "MUC Munich", "EDDB" to "BER Berlin", "EDDL" to "DUS Düsseldorf",
        "EDDH" to "HAM Hambourg", "EDDK" to "CGN Cologne", "EDDS" to "STR Stuttgart",
        "EDDN" to "NUE Nuremberg", "LSZH" to "ZRH Zurich", "LSGG" to "GVA Genève",
        "LOWW" to "VIE Vienne", "LKPR" to "PRG Prague", "EPWA" to "WAW Varsovie",
        "EPKK" to "KRK Cracovie", "LHBP" to "BUD Budapest", "LROP" to "OTP Bucarest",
        "LBSF" to "SOF Sofia", "LGAV" to "ATH Athènes", "LGTS" to "SKG Thessalonique",
        "LGIR" to "HER Héraklion", "LGRP" to "RHO Rhodes", "LCLK" to "LCA Larnaca",
        "LMML" to "MLA Malte", "LEMD" to "MAD Madrid", "LEBL" to "BCN Barcelone",
        "LEPA" to "PMI Palma", "LEMG" to "AGP Malaga", "LEAL" to "ALC Alicante",
        "LEVC" to "VLC Valence", "LEZL" to "SVQ Séville", "LEBB" to "BIO Bilbao",
        "LEIB" to "IBZ Ibiza", "LEMH" to "MAH Minorque", "GCTS" to "TFS Ténérife-Sud",
        "GCXO" to "TFN Ténérife-Nord", "GCLP" to "LPA Las Palmas", "GCRR" to "ACE Lanzarote",
        "GCFV" to "FUE Fuerteventura", "LPPT" to "LIS Lisbonne", "LPPR" to "OPO Porto",
        "LPFR" to "FAO Faro", "LPMA" to "FNC Madère", "LIRF" to "FCO Rome",
        "LIML" to "LIN Milan-Linate", "LIMC" to "MXP Milan-Malpensa", "LIME" to "BGY Bergame",
        "LIPZ" to "VCE Venise", "LIRN" to "NAP Naples", "LICC" to "CTA Catane",
        "LIPE" to "BLQ Bologne", "LIRQ" to "FLR Florence", "LIMF" to "TRN Turin",
        "EKCH" to "CPH Copenhague", "ENGM" to "OSL Oslo", "ESSA" to "ARN Stockholm",
        "EFHK" to "HEL Helsinki", "BIKF" to "KEF Reykjavik", "EVRA" to "RIX Riga",
        "EYVI" to "VNO Vilnius", "EETN" to "TLL Tallinn", "LTFM" to "IST Istanbul",
        "LTFJ" to "SAW Istanbul-SAW", "LTAI" to "AYT Antalya", "LDZA" to "ZAG Zagreb",
        "LDSP" to "SPU Split", "LDDU" to "DBV Dubrovnik", "LJLJ" to "LJU Ljubljana",
        "LYBE" to "BEG Belgrade", "LZIB" to "BTS Bratislava", "LWSK" to "SKP Skopje",
        "LATI" to "TIA Tirana", "UUEE" to "SVO Moscou", "UUDD" to "DME Moscou",
        "UKBB" to "KBP Kiev",
        // Afrique & Moyen-Orient
        "GMMN" to "CMN Casablanca", "GMMX" to "RAK Marrakech", "GMAD" to "AGA Agadir",
        "GMFF" to "FEZ Fès", "GMTT" to "TNG Tanger", "DAAG" to "ALG Alger",
        "DAOO" to "ORN Oran", "DTTA" to "TUN Tunis", "DTMB" to "MIR Monastir",
        "DTNH" to "NBE Enfidha", "HECA" to "CAI Le Caire", "HEGN" to "HRG Hurghada",
        "HESH" to "SSH Charm el-Cheikh", "GOBD" to "DSS Dakar", "DIAP" to "ABJ Abidjan",
        "DFFD" to "OUA Ouagadougou", "DBBB" to "COO Cotonou", "FCBB" to "BZV Brazzaville",
        "FKYS" to "NSI Yaoundé", "FKKD" to "DLA Douala", "HKJK" to "NBO Nairobi",
        "HAAB" to "ADD Addis-Abeba", "FAOR" to "JNB Johannesburg", "FACT" to "CPT Le Cap",
        "FMMI" to "TNR Antananarivo", "FIMP" to "MRU Maurice", "FSIA" to "SEZ Seychelles",
        "OMDB" to "DXB Dubaï", "OMAA" to "AUH Abou Dabi", "OTHH" to "DOH Doha",
        "OERK" to "RUH Riyad", "OEJN" to "JED Djeddah", "OKKK" to "KWI Koweït",
        "LLBG" to "TLV Tel Aviv", "OLBA" to "BEY Beyrouth", "OJAI" to "AMM Amman",
        // Amériques
        "KJFK" to "JFK New York", "KEWR" to "EWR Newark", "KBOS" to "BOS Boston",
        "KIAD" to "IAD Washington", "KATL" to "ATL Atlanta", "KORD" to "ORD Chicago",
        "KMIA" to "MIA Miami", "KLAX" to "LAX Los Angeles", "KSFO" to "SFO San Francisco",
        "KDFW" to "DFW Dallas", "KSEA" to "SEA Seattle", "KIAH" to "IAH Houston",
        "CYUL" to "YUL Montréal", "CYYZ" to "YYZ Toronto", "CYVR" to "YVR Vancouver",
        "CYQB" to "YQB Québec", "MMMX" to "MEX Mexico", "MMUN" to "CUN Cancún",
        "SBGR" to "GRU São Paulo", "SBGL" to "GIG Rio de Janeiro", "SAEZ" to "EZE Buenos Aires",
        "SCEL" to "SCL Santiago", "SKBO" to "BOG Bogota", "SPJC" to "LIM Lima",
        "MDPC" to "PUJ Punta Cana", "MTPP" to "PAP Port-au-Prince", "TNCM" to "SXM St-Martin",
        // Asie & Océanie
        "VABB" to "BOM Mumbai", "VIDP" to "DEL Delhi", "VTBS" to "BKK Bangkok",
        "WSSS" to "SIN Singapour", "WMKK" to "KUL Kuala Lumpur", "WIII" to "CGK Jakarta",
        "RJTT" to "HND Tokyo-Haneda", "RJAA" to "NRT Tokyo-Narita", "RKSI" to "ICN Séoul",
        "ZBAA" to "PEK Pékin", "ZBAD" to "PKX Pékin-Daxing", "ZSPD" to "PVG Shanghai",
        "ZGGG" to "CAN Canton", "VHHH" to "HKG Hong Kong", "RCTP" to "TPE Taipei",
        "VVNB" to "HAN Hanoï", "VVTS" to "SGN Hô-Chi-Minh", "RPLL" to "MNL Manille",
        "YSSY" to "SYD Sydney", "YMML" to "MEL Melbourne", "NZAA" to "AKL Auckland",
    )

    /** « LFPG » → « CDG Paris » ; code inconnu (ou "?") renvoyé tel quel. */
    fun display(code: String): String = TABLE[code.uppercase()] ?: code
}
