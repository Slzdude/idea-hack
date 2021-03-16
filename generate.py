from urllib.parse import urljoin

import requests
from lxml import etree
from uuid import uuid4
import zipfile
import glob
import io
import os

base_url = ""
if os.path.exists("CNAME"):
    base_url = "https://" + open("CNAME").read()

plugins_xml = etree.Element("plugins")

for file in glob.glob("files/*.zip"):
    with zipfile.ZipFile(file) as plugin_zip:
        main_folder = plugin_zip.filelist[0]
        if not main_folder.is_dir():
            raise Exception("not only a directory in zip")
        basename = os.path.splitext(os.path.basename(file))[0]
        for i in plugin_zip.filelist:
            if i.is_dir():
                continue
            if not i.filename.endswith(".jar"):
                continue
            target_buffer = io.BytesIO(plugin_zip.read(i.filename))
            with zipfile.ZipFile(target_buffer) as target_zip:
                if "META-INF/plugin.xml" not in target_zip.namelist():
                    continue
                xml = target_zip.read("META-INF/plugin.xml")
                print("reading xml with length:", len(xml))
                parser = etree.XMLParser(encoding="iso8859-1", strip_cdata=False)
                plugin_xml = etree.XML(xml, parser)
                plugin_element = etree.SubElement(
                    plugins_xml,
                    "plugin",
                    attrib={
                        "id": plugin_xml.findtext("./id"),
                        "url": urljoin(base_url, file.replace("\\", "/")),
                        "version": plugin_xml.findtext("./version"),
                    },
                )
                plugin_element.append(plugin_xml.find("./idea-version"))
                plugin_element.append(plugin_xml.find("./name"))
                plugin_element.append(plugin_xml.find("./vendor"))
                etree.SubElement(plugin_element, "rating").text = "5"
                e = plugin_xml.find("./description")
                e.text = etree.CDATA(e.text)
                plugin_element.append(e)
                e = plugin_xml.find("./change-notes")
                e.text = etree.CDATA(e.text)
                plugin_element.append(e)
                print("added plugin:", plugin_xml.findtext("./id"))
    print("=" * 32)

open("updatePlugins.xml", "wb").write(
    etree.tostring(
        plugins_xml, pretty_print=True, encoding="iso8859-1", xml_declaration=True
    )
)
