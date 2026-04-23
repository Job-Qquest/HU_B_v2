# Hu_B_v2 
Hu_B_v2 is een chatbot voor vragen rondom de Qquest personeelsgids

##Installatie
1. Schaf een API-key aan (bijvoorbeeld door een persoonlijk account op OpenAI aan te maken en hier credit op te zetten). https://auth.openai.com/create-account.
2. Sla de link naar de API-Key op in Windows Powershell (zie functioneel ontwerp voor meer instructies).
4. Installeer NetBeans 29 (Java). https://netbeans.apache.org/front/main/download/nb29/
5. Navigeer in NetBeans naar Team > Git > Clone > voer de github link in (https://github.com/Job-Qquest/HU_B_v2), en daaronder je eigen GitHub accountgegevens.
6. Je hebt nu de meest recente versie van Hu_B_v2.
7. Run de code en de chatbot verschijnt in een apart venster.

### Gebruik
De gebruik stelt een vraag. Hu_B_v2 beantwoord deze vraag. 
Als de informatie in de personeelsgids of gelinkte docuemnten staat geeft hij inhoudelijk antwoord. 
Bij onvoldoende informatie niet geeft hij de gebruiker een advies voor vervolgstappen om antwoord op diens vraag te krijgen. 

#### Functionaliteiten
Hu_B_v2 gebruikt hiervoor de volgende functionaliteiten:
- Hu_B_v2 heeft een 'context window' van waaruit hij, binnen (niet tussen) sessies gegeven informatie, meeneemt voor het formuleren van een gericht antwoord.
- Hu_B_v2 vraagt zelf door naar meer context en geeft het ook aan wanneer hij onvoldoende informatie heeft voor het beantwoorden van de vraag.
- Hu_B_v2 kan vragen met typfouten alsnog interpreteren.
- Hu_B_v2 kan de gebruiker helpen een email op te stellen naar een leidinggevende.

##### Bron updates
Hu_B_v2 laad de personeelsgids en alle externe bronnen bij de eerste keer opstarten in. 
Zodra een bron word aangepast zal Hu_B_v2 bij alle gebruikers opnieuw moeten opstarten om alle chunks opnieuw te maken.
(doet hij dit automatisch? komt er een melding? etc.)

###### Informatie voor beheerders
....
