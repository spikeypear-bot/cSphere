// Photos and display categories for the equipment catalogue (TS09).
// Keys are the catalogue's display names, exactly as DevEquipmentSeeder writes
// them to equipments.equipment_name. Categories live here, not in the database:
// equipments.equipment_type is still the agreed general code 'E' until the team
// settles a code table (SCHEMA.md §5).
//
// Every photo is from Wikimedia Commons and resized to at most 640px. Credit is
// required by most of these licences, so render EquipmentImageCredits wherever
// the photos appear.
import audioMixer from '../../assets/equipment/audio-mixer.jpg'
import badgeLanyards from '../../assets/equipment/badge-lanyards.jpg'
import badgePrinter from '../../assets/equipment/badge-printer.jpg'
import barcodeScanner from '../../assets/equipment/barcode-scanner.jpg'
import cameraTripod from '../../assets/equipment/camera-tripod.jpg'
import confidenceMonitor from '../../assets/equipment/confidence-monitor.jpg'
import extensionCord from '../../assets/equipment/extension-cord.jpg'
import hdmiCable from '../../assets/equipment/hdmi-cable.jpg'
import hdmiUsbCAdapter from '../../assets/equipment/hdmi-usb-c-adapter.jpg'
import ipad from '../../assets/equipment/ipad.jpg'
import ipadDeskStand from '../../assets/equipment/ipad-desk-stand.jpg'
import laptop from '../../assets/equipment/laptop.jpg'
import lecternMicrophone from '../../assets/equipment/lectern-microphone.jpg'
import mobileWiFi from '../../assets/equipment/mobile-wi-fi.jpg'
import nfcReader from '../../assets/equipment/nfc-reader.jpg'
import portableRouter from '../../assets/equipment/portable-router.jpg'
import portableSpeaker from '../../assets/equipment/portable-speaker.jpg'
import powerBank from '../../assets/equipment/power-bank.jpg'
import projectionScreen from '../../assets/equipment/projection-screen.jpg'
import projector from '../../assets/equipment/projector.jpg'
import ringLight from '../../assets/equipment/ring-light.jpg'
import usbCChargingHub from '../../assets/equipment/usb-c-charging-hub.jpg'
import walkieTalkie from '../../assets/equipment/walkie-talkie.jpg'
import webcam from '../../assets/equipment/webcam.jpg'
import wirelessMicrophone from '../../assets/equipment/wireless-microphone.jpg'
import wirelessPresenter from '../../assets/equipment/wireless-presenter.jpg'

export type EquipmentCategory =
  | 'Audiovisual'
  | 'Registration'
  | 'Connectivity'
  | 'Accessories'
  | 'Computing & Streaming'

export interface ImageCredit {
  title: string
  author: string
  license: string
  /** Empty for public-domain images, which have no licence deed. */
  licenseUrl: string
  sourceUrl: string
}

export interface EquipmentMedia {
  image: string
  category: EquipmentCategory
  credit: ImageCredit
}

const CC_BY_2 = 'https://creativecommons.org/licenses/by/2.0'
const CC_BY_3 = 'https://creativecommons.org/licenses/by/3.0'
const CC_BY_4 = 'https://creativecommons.org/licenses/by/4.0'
const CC_BY_SA_2 = 'https://creativecommons.org/licenses/by-sa/2.0'
const CC_BY_SA_3 = 'https://creativecommons.org/licenses/by-sa/3.0'
const CC_BY_SA_4 = 'https://creativecommons.org/licenses/by-sa/4.0'
const CC0 = 'https://creativecommons.org/publicdomain/zero/1.0'
const COMMONS = 'https://commons.wikimedia.org/wiki/File:'

// No suitable freely licensed photo was found for 'iPad Floor Stand' or
// 'Badge Printer Cards'; equipmentMediaFor returns undefined for them, so pages
// should fall back to a placeholder.
export const EQUIPMENT_MEDIA: Readonly<Record<string, EquipmentMedia>> = {
  // Audiovisual
  Projector: {
    image: projector,
    category: 'Audiovisual',
    credit: { title: 'Epson EB-U04', author: 'Raimond Spekking', license: 'CC BY-SA 4.0', licenseUrl: CC_BY_SA_4, sourceUrl: `${COMMONS}Epson_EB-U04-5362.jpg` },
  },
  'Wireless Microphone': {
    image: wirelessMicrophone,
    category: 'Audiovisual',
    credit: { title: 'Shure Wireless Microphone SM58', author: 'Emilian Robert Vicol', license: 'CC BY 2.0', licenseUrl: CC_BY_2, sourceUrl: `${COMMONS}Shure_Wireless_Microphone_SM58.jpg` },
  },
  'Portable Speaker': {
    image: portableSpeaker,
    category: 'Audiovisual',
    credit: { title: 'JBL Charge 3 portable Bluetooth speaker', author: 'Masahiko OHKUBO', license: 'CC BY 2.0', licenseUrl: CC_BY_2, sourceUrl: 'https://commons.wikimedia.org/wiki/File:JBL_CHARGE3_%E3%81%A8%E3%81%84%E3%81%86_Portable_Bluetooth_Speaker_%E3%80%81%E7%96%B2%E3%82%8C%E3%81%AA%E3%81%84%E9%9F%B3%E3%81%A7%E3%81%99%E3%81%AD%E3%80%82%E8%BB%8A%E8%BC%89%E3%81%99%E3%82%8B%E3%82%88%EF%BC%81_(28494681635).jpg' },
  },
  'Audio Mixer': {
    image: audioMixer,
    category: 'Audiovisual',
    credit: { title: 'Yamaha M7CL digital live-sound mixing console', author: 'beeki', license: 'CC0', licenseUrl: CC0, sourceUrl: `${COMMONS}Yamaha_M7CL_digital_live-sound_mixing_console_-_left_half_angled.jpg` },
  },
  'Walkie-Talkie': {
    image: walkieTalkie,
    category: 'Audiovisual',
    credit: { title: 'Motorola T5320 FRS Radio', author: 'Tim from United States', license: 'CC BY 2.0', licenseUrl: CC_BY_2, sourceUrl: `${COMMONS}Motorola_T5320_FRS_Radio.jpg` },
  },
  'Projection Screen': {
    image: projectionScreen,
    category: 'Audiovisual',
    credit: { title: 'Soirée WikiCheese le 24 octobre 2015 - 39', author: 'Lionel Allorge', license: 'CC BY-SA 3.0', licenseUrl: CC_BY_SA_3, sourceUrl: `${COMMONS}Soir%C3%A9e_WikiCheese_le_24_octobre_2015_-_39.jpg` },
  },
  'Wireless Presenter': {
    image: wirelessPresenter,
    category: 'Audiovisual',
    credit: { title: 'August LP205R Wireless Presenter', author: 'Katie Chan', license: 'CC BY-SA 3.0', licenseUrl: CC_BY_SA_3, sourceUrl: `${COMMONS}August_LP205R_Wireless_Presenter.jpg` },
  },
  'Confidence Monitor': {
    image: confidenceMonitor,
    category: 'Audiovisual',
    credit: { title: 'LCD monitor', author: 'Noles1984', license: 'CC BY-SA 3.0', licenseUrl: CC_BY_SA_3, sourceUrl: `${COMMONS}LCD_monitor.png` },
  },
  'Lectern Microphone': {
    image: lecternMicrophone,
    category: 'Audiovisual',
    credit: { title: 'Gooseneck microphone on podium with stage bokeh lights', author: 'A S M Jobaer', license: 'CC BY-SA 4.0', licenseUrl: CC_BY_SA_4, sourceUrl: `${COMMONS}Gooseneck_microphone_on_podium_with_stage_bokeh_lights_01.jpg` },
  },

  // Registration
  iPad: {
    image: ipad,
    category: 'Registration',
    credit: { title: 'iPad Air 2', author: 'Netspy', license: 'CC BY 3.0', licenseUrl: CC_BY_3, sourceUrl: `${COMMONS}IPad_Air_2_Wikipedia.png` },
  },
  'iPad Desk Stand': {
    image: ipadDeskStand,
    category: 'Registration',
    credit: { title: 'Wooden iPad stand - TNW Conference 2011', author: 'Julia de Boer / The Next Web', license: 'CC BY-SA 2.0', licenseUrl: CC_BY_SA_2, sourceUrl: `${COMMONS}Wooden_iPad_stand_-_TNW_Conference_2011_-_Day_2_(5669344207).jpg` },
  },
  'Badge Printer': {
    image: badgePrinter,
    category: 'Registration',
    credit: { title: 'SMART Cardprinter closed', author: 'Michael Ahrens', license: 'CC BY-SA 3.0', licenseUrl: CC_BY_SA_3, sourceUrl: `${COMMONS}SMART_Cardprinter_closed.png` },
  },
  'Badge Lanyards': {
    image: badgeLanyards,
    category: 'Registration',
    credit: { title: 'LinuxDay Milano 2022 gadget lanyard', author: 'UnixMiB community', license: 'CC0', licenseUrl: CC0, sourceUrl: `${COMMONS}LinuxDay_Milano_2022_gadget_lanyard.jpg` },
  },

  // Connectivity
  'Mobile Wi-Fi': {
    image: mobileWiFi,
    category: 'Connectivity',
    credit: { title: 'Huawei E5', author: 'Huawei', license: 'CC BY 2.0', licenseUrl: CC_BY_2, sourceUrl: `${COMMONS}Huawei_E5.jpg` },
  },
  'Portable Router': {
    image: portableRouter,
    category: 'Connectivity',
    credit: { title: 'Travel router and power bank', author: 'Danielrosehill', license: 'CC BY 4.0', licenseUrl: CC_BY_4, sourceUrl: `${COMMONS}Travel_router_and_power_bank.jpg` },
  },

  // Accessories
  'Power Bank': {
    image: powerBank,
    category: 'Accessories',
    credit: { title: 'USB power bank', author: 'Ronggy', license: 'Public domain', licenseUrl: '', sourceUrl: `${COMMONS}USB_power_bank.jpg` },
  },
  'NFC Reader': {
    image: nfcReader,
    category: 'Accessories',
    credit: { title: 'Tappr Card Reader NFC', author: 'SteveMccabe45', license: 'CC BY-SA 4.0', licenseUrl: CC_BY_SA_4, sourceUrl: `${COMMONS}Tappr_Card_Reader_NFC.jpg` },
  },
  'Barcode Scanner': {
    image: barcodeScanner,
    category: 'Accessories',
    credit: { title: 'Honeywell 4600g', author: 'Raimond Spekking', license: 'CC BY-SA 4.0', licenseUrl: CC_BY_SA_4, sourceUrl: `${COMMONS}Honeywell_4600g-8663.jpg` },
  },
  'HDMI Cable': {
    image: hdmiCable,
    category: 'Accessories',
    credit: { title: 'HDMI connector', author: 'Evan-Amos', license: 'Public domain', licenseUrl: '', sourceUrl: `${COMMONS}HDMI-Connector.jpg` },
  },
  'Extension Cord': {
    image: extensionCord,
    category: 'Accessories',
    credit: { title: 'Power strip of National', author: 'Yapparina', license: 'CC0', licenseUrl: CC0, sourceUrl: `${COMMONS}Power_strip_of_National.jpg` },
  },
  'USB-C Charging Hub': {
    image: usbCChargingHub,
    category: 'Accessories',
    credit: { title: 'USB-C hub, 5 ports', author: 'Khalid Bencherifa', license: 'CC BY-SA 4.0', licenseUrl: CC_BY_SA_4, sourceUrl: `${COMMONS}USB-C_Hubb_5_portar.jpg` },
  },
  'HDMI/USB-C Adapter': {
    image: hdmiUsbCAdapter,
    category: 'Accessories',
    credit: { title: 'USB-C Digital AV Multiport Adapter', author: 'Thefreeencyclopedia1', license: 'CC BY-SA 4.0', licenseUrl: CC_BY_SA_4, sourceUrl: `${COMMONS}USB-C_Digital_AV_Multiport_Adapter.jpeg` },
  },

  // Computing & Streaming
  Laptop: {
    image: laptop,
    category: 'Computing & Streaming',
    credit: { title: 'IBM ThinkPad R51', author: 'André Karwath aka Aka', license: 'CC BY-SA 4.0', licenseUrl: CC_BY_SA_4, sourceUrl: `${COMMONS}IBM_Thinkpad_R51.jpg` },
  },
  Webcam: {
    image: webcam,
    category: 'Computing & Streaming',
    credit: { title: 'USB webcam for PC', author: 'WrS.tm.pl', license: 'CC0', licenseUrl: CC0, sourceUrl: `${COMMONS}USB_webcam_for_PC.jpg` },
  },
  'Camera Tripod': {
    image: cameraTripod,
    category: 'Computing & Streaming',
    credit: { title: 'Gitzo 1541T Traveler carbon fibre tripod', author: 'Lucasbosch', license: 'CC BY-SA 4.0', licenseUrl: CC_BY_SA_4, sourceUrl: `${COMMONS}Gitzo_1541T_Traveler_Carbon_Fiber_Tripod_Collage_Horizontal.jpg` },
  },
  'Ring Light': {
    image: ringLight,
    category: 'Computing & Streaming',
    credit: { title: 'Ring light', author: 'notStudio - studio fotograficzne', license: 'CC BY-SA 4.0', licenseUrl: CC_BY_SA_4, sourceUrl: `${COMMONS}RING_1000x_IMG_5024.jpg` },
  },
}

const BY_LOWER_NAME = new Map(
  Object.entries(EQUIPMENT_MEDIA).map(([name, media]) => [name.toLowerCase(), media]),
)

/** Photo and category for a catalogue name, ignoring case and surrounding spaces. */
export function equipmentMediaFor(name: string | null | undefined): EquipmentMedia | undefined {
  if (!name) return undefined
  return BY_LOWER_NAME.get(name.trim().toLowerCase())
}
