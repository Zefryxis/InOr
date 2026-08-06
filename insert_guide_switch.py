import json, os

base = r"C:/Users/mihal/Documents/InventoryOrganizerMod/src/main/resources/assets/inventory-organizer/lang"

translations = {
    "en_us": {
        "inventory-organizer.guide.switch.head": "Auto Tool Switch",
        "inventory-organizer.guide.switch.t1": "Enable 'Auto switch' mode in the Inv tab: designate one hotbar slot as the Switch slot and one or more inventory slots as storage. The mod automatically puts the right tool (pickaxe for stone, axe for wood, sword for mobs) into your Switch slot as you look around.",
        "inventory-organizer.guide.switch.t2": "Configure tool groups under Ranks -> switch categories. Each category (Pickaxe / Axe / Shovel / Hoe / Mob weapon) can be assigned a group -- Ranks picks the best item in that group. 'On air' sets what happens when you look at empty space: keep, restore the last item, or swap to a specific tool type.",
        "inventory-organizer.guide.switch.t3": "Trigger mode (Trigger button in the tab): 'Auto' swaps immediately whenever your crosshair target changes -- single player only. 'Button' mode works everywhere (servers too): press the 'Switch: Trigger Tool Swap' keybind to manually trigger the swap.",
        "inventory-organizer.guide.switch.d1": "In Auto mode the attack cooldown ticker counts up in your inventory so the tool is already ready when it reaches your hand.",
    },
    "de_de": {
        "inventory-organizer.guide.switch.head": "Automatischer Werkzeugwechsel",
        "inventory-organizer.guide.switch.t1": "Aktiviere den Modus 'Auto-Wechsel' im Inventar-Tab: lege einen Hotbar-Slot als Wechsel-Slot und einen oder mehrere Inventar-Slots als Lager fest. Der Mod setzt automatisch das richtige Werkzeug (Spitzhacke fur Stein, Axt fur Holz, Schwert fur Mobs) in deinen Wechsel-Slot, wahrend du dich umschaust.",
        "inventory-organizer.guide.switch.t2": "Konfiguriere Werkzeuggruppen unter Range -> Wechsel-Kategorien. Jeder Kategorie (Spitzhacke / Axt / Schaufel / Hacke / Mob-Waffe) kann eine Gruppe zugewiesen werden -- Range wahlt das beste Item aus. 'Auf Luft' legt fest, was passiert, wenn du in leeren Raum schaust: behalten, letztes Item wiederherstellen oder zu einem bestimmten Werkzeugtyp wechseln.",
        "inventory-organizer.guide.switch.t3": "Ausloeser-Modus (Ausloeser-Schalter im Tab): 'Auto' wechselt sofort, wenn sich dein Fadenkreuz-Ziel andert -- nur im Einzelspieler. 'Taste'-Modus funktioniert uberall (auch auf Servern): drucke den Tastenbelegungseintrag 'Wechsel: Werkzeugwechsel auslosen' fur einen manuellen Wechsel.",
        "inventory-organizer.guide.switch.d1": "Im Auto-Modus tickt der Angriffscooldown-Zahler bereits im Inventar hoch, sodass das Werkzeug sofort einsatzbereit ist, wenn es in deine Hand kommt.",
    },
    "es_es": {
        "inventory-organizer.guide.switch.head": "Cambio automatico de herramienta",
        "inventory-organizer.guide.switch.t1": "Activa el modo 'Auto switch' en la pestana Inv: designa un hueco de la barra de acceso rapido como hueco de cambio y uno o mas huecos del inventario como almacen. El mod pone automaticamente la herramienta correcta (pico para piedra, hacha para madera, espada para mobs) en tu hueco de cambio mientras miras alrededor.",
        "inventory-organizer.guide.switch.t2": "Configura los grupos de herramientas en Rangos -> categorias de cambio. Cada categoria (Pico / Hacha / Pala / Azada / Arma para mobs) puede asignarse a un grupo -- Rangos elige el mejor objeto. 'En el aire' define que ocurre al mirar al espacio vacio: mantener, restaurar el ultimo objeto o cambiar a un tipo de herramienta concreto.",
        "inventory-organizer.guide.switch.t3": "Modo de activacion (boton Activador en la pestana): 'Auto' cambia inmediatamente cuando cambia el objetivo de tu mira -- solo en modo un jugador. El modo 'Boton' funciona en cualquier lugar (tambien en servidores): pulsa el atajo 'Switch: Activar cambio de herramienta' para activar el cambio manualmente.",
        "inventory-organizer.guide.switch.d1": "En modo Auto el contador de enfriamiento de ataque avanza en tu inventario, por lo que la herramienta ya esta lista en el momento en que llega a tu mano.",
    },
    "fr_fr": {
        "inventory-organizer.guide.switch.head": "Echange automatique d'outil",
        "inventory-organizer.guide.switch.t1": "Activez le mode 'Echange auto' dans l'onglet Inv : designez un emplacement de la barre d'acces rapide comme emplacement d'echange et un ou plusieurs emplacements d'inventaire comme stockage. Le mod place automatiquement le bon outil (pioche pour la pierre, hache pour le bois, epee pour les mobs) dans votre emplacement d'echange au fil de vos deplacements.",
        "inventory-organizer.guide.switch.t2": "Configurez les groupes d'outils sous Rangs -> categories d'echange. Chaque categorie (Pioche / Hache / Pelle / Houe / Arme anti-mobs) peut etre assignee a un groupe -- Rangs choisit le meilleur objet. 'Dans les airs' definit ce qui se passe quand vous regardez l'espace vide : conserver, restaurer le dernier objet ou basculer vers un type d'outil precis.",
        "inventory-organizer.guide.switch.t3": "Mode declencheur (bouton Declencheur dans l'onglet) : 'Auto' echange immediatement des que la cible de votre viseur change -- en solo uniquement. Le mode 'Bouton' fonctionne partout (y compris sur les serveurs) : appuyez sur la touche 'Switch : Declencher l'echange d'outil' pour lancer l'echange manuellement.",
        "inventory-organizer.guide.switch.d1": "En mode Auto, le compteur de temps de recharge d'attaque progresse dans votre inventaire -- l'outil est donc pret a l'emploi des qu'il atteint votre main.",
    },
    "hu_hu": {
        "inventory-organizer.guide.switch.head": "Automatikus eszkozvaltas",
        "inventory-organizer.guide.switch.t1": "Engedelyezd az 'Auto switch' modot az Inv fulon: jelolj ki egy hotbar-slotot valto-slotnak, es egy vagy tobb inventory-slotot tarolonak. A mod automatikusan a megfeleloe eszkozta helyezi a valto-slotba (csakany kohoz, balta fahoz, kard mobokhoz), mikozben korulnezel.",
        "inventory-organizer.guide.switch.t2": "A szerszamcsoportokat a Rangok -> valto-kategoriak alatt allithatod be. Minden kategoriahoz (Csakany / Balta / Aso / Kapa / Mobfegyver) rendelj egy csoportot -- a Rangok a legjobb eszkozta valasztja ki. A 'Levegore' beallitas meghatarozza, mi tortenik, ha ures teret nezel: tartsd meg, allitsd vissza az utolsot, vagy valts egy adott tipusra.",
        "inventory-organizer.guide.switch.t3": "Trigger mod (Trigger gomb a fulon): az 'Auto' azonnal valt, ha a celkeresztedbe kerulo objektum megvaltozik -- csak egyjatek modban. A 'Gomb' mod mindenhol mukodik (szerveren is): nyomj a 'Switch: Eszközvaltas inditasa' billentyu kombinaciora a manualis valtashoz.",
        "inventory-organizer.guide.switch.d1": "Auto modban a tamadas-cooldown szamlaoja az inventoryban is ketyeg, igy az eszkoz mar keszhasznalatra, amint a kezedbe kerul.",
    },
    "ja_jp": {
        "inventory-organizer.guide.switch.head": "自動ツール切り替え",
        "inventory-organizer.guide.switch.t1": "Invタブで『Autoスイッチ』モードを有効にする：ホットバーの1スロットをスイッチスロット、一つ以上のスロットをストレージに指定。周囲を見回すだけで最適なツール（石にはツルハシ、木にはオノ、モブには剣）がスイッチスロットに自動でセットされる。",
        "inventory-organizer.guide.switch.t2": "ツールグループはランク→スイッチカテゴリで設定する。各カテゴリ（ツルハシ／オノ／シャベル／クワ／対モブ武器）にグループを割り当て、ランクが最良のアイテムを選ぶ。『空中で』はターゲットなしのときの動作：保持、最後のアイテムを戻す、または特定のツールタイプへ切り替え。",
        "inventory-organizer.guide.switch.t3": "トリガーモード（タブのTriggerボタン）：『Auto』はターゲットが変わると即座に切り替え——シングルプレイヤーのみ。『ボタン』モードはどこでも使える（サーバーも可）：キーバインド『Switc：ツール切り替え起動』を押して手動で切り替える。",
        "inventory-organizer.guide.switch.d1": "Autoモードでは攻撃クールダウンがインベントリ内でも進むため、ツールが手に届いた瞬間にすぐ使用できる。",
    },
    "pt_br": {
        "inventory-organizer.guide.switch.head": "Troca automatica de ferramenta",
        "inventory-organizer.guide.switch.t1": "Ative o modo 'Auto switch' na aba Inv: defina um espaco da barra de atalho como espaco de troca e um ou mais espacos do inventario como armazenamento. O mod coloca automaticamente a ferramenta certa (picareta para pedra, machado para madeira, espada para mobs) no seu espaco de troca conforme voce olha ao redor.",
        "inventory-organizer.guide.switch.t2": "Configure os grupos de ferramentas em Classificacoes -> categorias de troca. Cada categoria (Picareta / Machado / Pa / Enxada / Arma para mobs) pode ser atribuida a um grupo -- Classificacoes escolhe o melhor item. 'No ar' define o que acontece ao olhar para o espaco vazio: manter, restaurar o ultimo item ou mudar para um tipo de ferramenta especifico.",
        "inventory-organizer.guide.switch.t3": "Modo de acionamento (botao Acionador na aba): 'Auto' troca imediatamente quando o alvo da mira muda -- apenas no modo single player. O modo 'Botao' funciona em qualquer lugar (inclusive em servidores): pressione o atalho 'Switch: Acionar Troca de Ferramenta' para acionar manualmente.",
        "inventory-organizer.guide.switch.d1": "No modo Auto o contador de recarga de ataque avanca no inventario, entao a ferramenta ja esta pronta para uso no momento em que chega a sua mao.",
    },
    "ru_ru": {
        "inventory-organizer.guide.switch.head": "Автоматическая смена инструмента",
        "inventory-organizer.guide.switch.t1": "Включите режим «Авто-переключение» на вкладке Инв: назначьте один слот панели быстрого доступа как слот переключения, а один или несколько слотов инвентаря — как хранилище. Мод автоматически помещает нужный инструмент (кирку для камня, топор для дерева, меч для мобов) в слот переключения, пока вы осматриваетесь.",
        "inventory-organizer.guide.switch.t2": "Группы инструментов настраиваются в разделе Ранги → категории переключения. Каждой категории (Кирка / Топор / Лопата / Мотыга / Оружие против мобов) можно назначить группу — Ранги выберут лучший предмет. Параметр «В воздухе» задает, что происходит при взгляде в пустое пространство: оставить, вернуть последний предмет или переключиться на определенный тип инструмента.",
        "inventory-organizer.guide.switch.t3": "Режим запуска (кнопка «Запуск» на вкладке): «Авто» — мгновенное переключение при смене цели прицела (только в одиночной игре). Режим «Кнопка» работает везде (в том числе на серверах): нажмите клавишу «Switch: запустить смену инструмента» для переключения вручную.",
        "inventory-organizer.guide.switch.d1": "В режиме «Авто» счётчик задержки атаки отсчитывается прямо в инвентаре — инструмент уже готов к использованию в момент, когда оказывается в руке.",
    },
    "zh_cn": {
        "inventory-organizer.guide.switch.head": "自动工具切换",
        "inventory-organizer.guide.switch.t1": "在物品栏标签中启用『自动切换』模式：将快捷栏中的一个格子指定为切换槽，将一个或多个格子指定为储存槽。当你环顾四周时，模组会自动将合适的工具（石头用镐、木头用斧、怪物用剑）放入切换槽。",
        "inventory-organizer.guide.switch.t2": "在『等级』→『切换类别』下配置工具组。每个类别（镐 / 斧 / 锹 / 锄 / 对怪物武器）可分配一个组，等级系统选出最佳物品。『在空中时』决定看向空旷处时的行为：保持、恢复上一件物品或切换到指定工具类型。",
        "inventory-organizer.guide.switch.t3": "触发模式（标签中的触发按鈕）：『自动』在准星目标改变时立即切换——仅限单人模式。『按键』模式可在任何地方使用（包括服务器）：按下『切换：触发工具切换』快捷键以手动触发。",
        "inventory-organizer.guide.switch.d1": "自动模式下攻击冷却计时器在物品栏中持续累积，因此工具到手时立即就绪。",
    },
}

insert_before = "inventory-organizer.guide.warehouse.head"

for lang, new_keys in translations.items():
    path = os.path.join(base, f"{lang}.json")
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    if "inventory-organizer.guide.switch.head" in data:
        for k, v in new_keys.items():
            data[k] = v
        print(f"Updated existing: {lang}")
    else:
        new_data = {}
        for k, v in data.items():
            if k == insert_before:
                for nk, nv in new_keys.items():
                    new_data[nk] = nv
            new_data[k] = v
        data = new_data
        print(f"Inserted: {lang}")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

print("All done.")
