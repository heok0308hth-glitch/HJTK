package com.ht.mesh

/** 처음 여는 사람을 위한 장면 세 개. 모두 HT-Scene 글이라 AI가 쓰는 것과 똑같은 형식이다 */
object Samples {
    val LAMP = """{"scene":"Desk lamp","materials":{"brass":{"color":"#C9A45C","metal":1,"rough":0.32},"shade":{"color":"#E9E3D6","rough":0.7},"oak":{"color":"#8A5A36","rough":0.55}},
 "objects":[
  {"name":"desk","shape":"box","size":[2.4,0.06,1.2],"at":[0,0.72,0],"mat":"oak"},
  {"name":"leg","shape":"box","size":[0.06,0.72,0.06],"at":[1.1,0.36,0.5],"mat":"oak","mods":[{"mirror":"x"},{"mirror":"z"}]},
  {"name":"base","shape":"cylinder","r":0.16,"h":0.03,"seg":48,"at":[-0.6,0.765,-0.2],"mat":"brass"},
  {"name":"arm","shape":"cylinder","r":0.012,"h":0.5,"seg":12,"at":[0,0.25,0],"rot":[0,0,-22],"parent":"base","mat":"brass"},
  {"name":"arm2","shape":"cylinder","r":0.012,"h":0.42,"seg":12,"at":[0.1,0.45,0],"rot":[0,0,70],"parent":"base","mat":"brass"},
  {"name":"head","shape":"lathe","profile":[[0.02,0.06],[0.05,0.05],[0.12,-0.04],[0.13,-0.06]],"seg":40,"at":[0.3,0.52,0],"rot":[0,0,30],"parent":"base","mat":"shade","smooth":true},
  {"name":"book","shape":"box","size":[0.32,0.05,0.24],"at":[0.45,0.775,0.15],"rot":[0,-18,0],"mat":"shade"}]}"""

    val CHAIR = """{"scene":"Lounge chair","materials":{"walnut":{"color":"#6B4429","rough":0.5},"felt":{"color":"#3E5C58","rough":0.9},"rug":{"color":"#CFC6B6","rough":1}},
 "objects":[
  {"name":"seat","shape":"box","size":[0.62,0.12,0.58],"round":0.05,"at":[0,0.44,0],"mat":"felt"},
  {"name":"back","shape":"box","size":[0.62,0.52,0.1],"round":0.045,"at":[0,0.76,-0.27],"rot":[-12,0,0],"mat":"felt"},
  {"name":"leg","shape":"cylinder","r":0.022,"h":0.4,"seg":16,"at":[0.25,0.2,0.22],"rot":[8,0,-6],"mat":"walnut","mods":[{"mirror":"x"},{"mirror":"z"}]},
  {"name":"rail","shape":"capsule","r":0.02,"h":0.62,"seg":16,"at":[0.33,0.56,-0.02],"rot":[90,0,0],"mat":"walnut","mods":[{"mirror":"x"}]},
  {"name":"rug","shape":"plane","size":[2.2,0,1.6],"mat":"rug"}],"materials2":{}}"""

    val ROBOT = """{"scene":"Courier robot","materials":{"shell":{"color":"#E9E6DF","rough":0.35},"ink":{"color":"#1C1C1E","rough":0.6},"signal":{"color":"#E4572E","rough":0.4},"concrete":{"color":"#6E6A64","rough":1}},
 "objects":[
  {"name":"body","shape":"box","size":[0.7,0.5,0.9],"round":0.12,"at":[0,0.45,0],"mat":"shell"},
  {"name":"wheel","shape":"torus","R":0.13,"r":0.06,"seg":32,"at":[0.4,0.19,0.3],"rot":[0,0,90],"mat":"ink","mods":[{"mirror":"x"},{"array":{"count":2,"offset":[0,0,-0.6]}}]},
  {"name":"mast","shape":"cylinder","r":0.025,"h":0.35,"seg":16,"at":[0,0.88,0.2],"mat":"ink"},
  {"name":"eye","shape":"sphere","r":0.07,"seg":24,"at":[0,0.2,0],"parent":"mast","mat":"signal"},
  {"name":"lid","shape":"box","size":[0.5,0.04,0.6],"at":[0,0.72,-0.08],"mat":"ink"},
  {"name":"floor","shape":"plane","size":[4,0,4],"mat":"concrete"}]}"""

    val ALL = listOf("책상 램프" to LAMP, "라운지 의자" to CHAIR, "배달 로봇" to ROBOT)
}
