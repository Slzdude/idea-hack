import requests
from lxml import etree

plugin_id = 7896
update_id = 97563

meta_url = "https://plugins.jetbrains.com/files/{0}/{1}/meta.json".format(
    plugin_id, update_id
)

print(meta_url)

data = requests.get(meta_url).json()


root = etree.Element("plugins")

plugin = etree.SubElement(
    root, "plugin", attrib={"id": data["xmlId"], "url": "", "version": data["version"]}
)
etree.SubElement(plugin, "idea-version", attrib={"since-build": data["since"]})
etree.SubElement(plugin, "name").text = data["name"]
etree.SubElement(
    plugin, "vendor", email=data.get("email", ""), url=data.get("url", "")
).text = data["vendor"]
etree.SubElement(plugin, "rating").text = "5"
etree.SubElement(plugin, "description").text = etree.CDATA(data.get("description", ""))
etree.SubElement(plugin, "change-notes").text = etree.CDATA(data.get("notes", ""))

open("updatePlugins.xml", "wb").write(
    etree.tostring(root, pretty_print=True, encoding="utf-8", xml_declaration=True)
)
