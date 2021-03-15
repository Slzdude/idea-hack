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
        basename = os.path.splitext(os.path.basename(file))[0]
        target = None
        for i in plugin_zip.filelist:
            if i.is_dir():
                continue
            if not i.filename.endswith(".jar"):
                continue
            if not i.filename.startswith(basename + "/lib/" + basename):
                continue
            target = i
        if not target:
            raise Exception("failed to get target with:", file)
        print("try parsing target jar:", target.filename)
        target_buffer = io.BytesIO(plugin_zip.read(target.filename))
        with zipfile.ZipFile(target_buffer) as target_zip:
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
