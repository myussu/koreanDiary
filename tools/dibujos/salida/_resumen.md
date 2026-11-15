# Resumen de dibujos pregenerados

Se dibujaron las **108 filas de `palabras.tsv`**, en su orden, en la rama
`dibujos/pregenerados`. Al comenzar, `salida/` solo contenía `.gitkeep`.
**No se saltó ninguna fila**: no había dibujos terminados que conservar ni
conflictos con las claves reservadas de la app.

Cada JSON se renderizó con `ver.py` y se abrió su PNG para revisar papel claro,
papel oscuro y la vista pequeña suavizada. Se corrigieron, entre otros detalles,
el contraste de los ojos, las uniones de las patas y el rotor, el apoyo de los
mástiles y el movimiento conjunto de las cuerdas y el asiento del columpio.
El faro usa franjas azules y una construcción lateral para distinguirse del ejemplo.

La validación individual y `python validar.py` al terminar dieron **108 dibujos,
0 errores y 0 avisos**. También se comprobó que cada fila conserva sus claves,
nombre, zona y tamaño, que el nombre del archivo coincide con su `id` y que
todos tienen PNG. Se generó y se abrió [la hoja completa](_hoja.png) mediante
`python ver.py --hoja`.

Los JSON son las fuentes de pinceladas; las vistas PNG y `_hoja.png` permanecen
locales, ignoradas por la configuración existente. Los dibujos suman 186 857 bytes.
Se hicieron diez commits de diez dibujos y un último commit con los ocho restantes
y este resumen, con mensajes en español y sin líneas de atribución.

## Inventario en el orden del TSV

| Fila | Dibujo | Claves | Archivo |
| --- | --- | --- | --- |
| 1 | gato | 고양이, 냥이 | [cat.json](cat.json) |
| 2 | perro | 개, 강아지, 멍멍이 | [dog.json](dog.json) |
| 3 | conejo | 토끼 | [rabbit.json](rabbit.json) |
| 4 | ardilla | 다람쥐 | [squirrel.json](squirrel.json) |
| 5 | paloma | 비둘기 | [pigeon.json](pigeon.json) |
| 6 | gorrión | 참새 | [sparrow.json](sparrow.json) |
| 7 | pájaro | 새 | [bird.json](bird.json) |
| 8 | gaviota | 갈매기 | [seagull.json](seagull.json) |
| 9 | cuervo | 까마귀 | [crow.json](crow.json) |
| 10 | pato | 오리 | [duck.json](duck.json) |
| 11 | cisne | 백조 | [swan.json](swan.json) |
| 12 | pez | 물고기 | [fish.json](fish.json) |
| 13 | ballena | 고래 | [whale.json](whale.json) |
| 14 | tortuga | 거북이 | [turtle.json](turtle.json) |
| 15 | mariposa | 나비 | [butterfly.json](butterfly.json) |
| 16 | abeja | 벌 | [bee.json](bee.json) |
| 17 | libélula | 잠자리 | [dragonfly.json](dragonfly.json) |
| 18 | luciérnaga | 반딧불이 | [firefly.json](firefly.json) |
| 19 | rana | 개구리 | [frog.json](frog.json) |
| 20 | caracol | 달팽이 | [snail.json](snail.json) |
| 21 | caballo | 말 | [horse.json](horse.json) |
| 22 | vaca | 소 | [cow.json](cow.json) |
| 23 | oveja | 양 | [sheep.json](sheep.json) |
| 24 | gallina | 닭 | [hen.json](hen.json) |
| 25 | ciervo | 사슴 | [deer.json](deer.json) |
| 26 | oso | 곰 | [bear.json](bear.json) |
| 27 | zorro | 여우 | [fox.json](fox.json) |
| 28 | pingüino | 펭귄 | [penguin.json](penguin.json) |
| 29 | búho | 부엉이 | [owl.json](owl.json) |
| 30 | girasol | 해바라기 | [sunflower.json](sunflower.json) |
| 31 | cactus | 선인장 | [cactus.json](cactus.json) |
| 32 | pino | 소나무 | [pine.json](pine.json) |
| 33 | cerezo | 벚나무 | [cherry_tree.json](cherry_tree.json) |
| 34 | arce | 단풍나무 | [maple_tree.json](maple_tree.json) |
| 35 | palmera | 야자수 | [palm_tree.json](palm_tree.json) |
| 36 | bambú | 대나무 | [bamboo.json](bamboo.json) |
| 37 | hongo | 버섯 | [mushroom.json](mushroom.json) |
| 38 | juncos | 갈대 | [reeds.json](reeds.json) |
| 39 | flor de loto | 연꽃 | [lotus.json](lotus.json) |
| 40 | maceta | 화분 | [potted_plant.json](potted_plant.json) |
| 41 | espantapájaros | 허수아비 | [scarecrow.json](scarecrow.json) |
| 42 | casa | 집 | [house.json](house.json) |
| 43 | casa tradicional coreana | 한옥 | [hanok.json](hanok.json) |
| 44 | escuela | 학교 | [school.json](school.json) |
| 45 | iglesia | 교회 | [church.json](church.json) |
| 46 | templo | 절 | [temple.json](temple.json) |
| 47 | pagoda | 탑 | [pagoda.json](pagoda.json) |
| 48 | castillo | 성 | [castle.json](castle.json) |
| 49 | faro | 등대 | [lighthouse.json](lighthouse.json) |
| 50 | molino de viento | 풍차 | [windmill.json](windmill.json) |
| 51 | carpa | 텐트 | [tent.json](tent.json) |
| 52 | banca | 벤치 | [bench.json](bench.json) |
| 53 | buzón | 우체통 | [mailbox.json](mailbox.json) |
| 54 | farola | 가로등 | [street_lamp.json](street_lamp.json) |
| 55 | semáforo | 신호등 | [traffic_light.json](traffic_light.json) |
| 56 | pabellón | 정자 | [pavilion.json](pavilion.json) |
| 57 | pozo | 우물 | [well.json](well.json) |
| 58 | cerca | 울타리 | [fence.json](fence.json) |
| 59 | columpio | 그네 | [swing.json](swing.json) |
| 60 | tobogán | 미끄럼틀 | [slide.json](slide.json) |
| 61 | fuente | 분수 | [fountain.json](fountain.json) |
| 62 | café | 카페 | [cafe.json](cafe.json) |
| 63 | tienda | 편의점 | [convenience_store.json](convenience_store.json) |
| 64 | bicicleta | 자전거 | [bicycle.json](bicycle.json) |
| 65 | auto | 자동차 | [car.json](car.json) |
| 66 | bus | 버스 | [bus.json](bus.json) |
| 67 | taxi | 택시 | [taxi.json](taxi.json) |
| 68 | camión | 트럭 | [truck.json](truck.json) |
| 69 | moto | 오토바이 | [motorcycle.json](motorcycle.json) |
| 70 | tren | 기차 | [train.json](train.json) |
| 71 | velero | 보트, 돛단배 | [sailboat.json](sailboat.json) |
| 72 | barco | 유람선 | [cruise_ship.json](cruise_ship.json) |
| 73 | avión | 비행기 | [airplane.json](airplane.json) |
| 74 | helicóptero | 헬리콥터 | [helicopter.json](helicopter.json) |
| 75 | globo aerostático | 열기구 | [hot_air_balloon.json](hot_air_balloon.json) |
| 76 | cometa | 연 | [kite.json](kite.json) |
| 77 | globo | 풍선 | [balloon.json](balloon.json) |
| 78 | paraguas | 우산 | [umbrella.json](umbrella.json) |
| 79 | muñeco de nieve | 눈사람 | [snowman.json](snowman.json) |
| 80 | castillo de arena | 모래성 | [sandcastle.json](sandcastle.json) |
| 81 | fogata | 모닥불 | [campfire.json](campfire.json) |
| 82 | vela | 촛불 | [candle.json](candle.json) |
| 83 | farol | 등불, 랜턴 | [lantern.json](lantern.json) |
| 84 | farol de papel | 연등 | [paper_lantern.json](paper_lantern.json) |
| 85 | fuegos artificiales | 불꽃놀이 | [fireworks.json](fireworks.json) |
| 86 | bandera | 깃발 | [flag.json](flag.json) |
| 87 | canasta | 바구니 | [basket.json](basket.json) |
| 88 | árbol de navidad | 크리스마스트리 | [christmas_tree.json](christmas_tree.json) |
| 89 | rayo | 번개 | [lightning.json](lightning.json) |
| 90 | aurora | 오로라 | [aurora.json](aurora.json) |
| 91 | estrella fugaz | 별똥별 | [shooting_star.json](shooting_star.json) |
| 92 | isla | 섬 | [island.json](island.json) |
| 93 | cascada | 폭포 | [waterfall.json](waterfall.json) |
| 94 | desierto | 사막 | [desert.json](desert.json) |
| 95 | roca | 바위 | [rock.json](rock.json) |
| 96 | cueva | 동굴 | [cave.json](cave.json) |
| 97 | volcán | 화산 | [volcano.json](volcano.json) |
| 98 | hielo | 얼음 | [ice.json](ice.json) |
| 99 | delfín | 돌고래 | [dolphin.json](dolphin.json) |
| 100 | cangrejo | 게 | [crab.json](crab.json) |
| 101 | concha | 조개 | [seashell.json](seashell.json) |
| 102 | estrella de mar | 불가사리 | [starfish.json](starfish.json) |
| 103 | medusa | 해파리 | [jellyfish.json](jellyfish.json) |
| 104 | noria | 관람차 | [ferris_wheel.json](ferris_wheel.json) |
| 105 | manta de picnic | 돗자리 | [picnic_blanket.json](picnic_blanket.json) |
| 106 | mono | 원숭이 | [monkey.json](monkey.json) |
| 107 | panda | 판다 | [panda.json](panda.json) |
| 108 | elefante | 코끼리 | [elephant.json](elephant.json) |
