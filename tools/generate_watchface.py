import xml.etree.ElementTree as ET
import os
import sys

def create_hand_xml_nodes(instrument, hand_type):
    index = instrument.get('index')
    image = instrument.get('image')
    ambient = instrument.get('ambient') or image.replace('_hand', '_ambient_hand')
    
    if hand_type == 'hour':
        x, y, w, h = instrument.get('h_x'), instrument.get('h_y'), instrument.get('h_w'), instrument.get('h_h')
        config = "hour_idx"
    else:
        x, y, w, h = instrument.get('m_x'), instrument.get('m_y'), instrument.get('m_w'), instrument.get('m_h')
        config = "min_idx"

    # Active
    active = ET.Element('PartImage', x=x, y=y, width=w, height=h)
    ET.SubElement(active, 'Image', resource=image)
    ET.SubElement(active, 'Variant', mode="AMBIENT", target="alpha", value="0")
    ET.SubElement(active, 'Transform', target="scaleX", value=f"[CONFIGURATION.{config}] == {index} ? 1 : 0")
    ET.SubElement(active, 'Transform', target="scaleY", value=f"[CONFIGURATION.{config}] == {index} ? 1 : 0")

    # Ambient
    ambient_el = ET.Element('PartImage', x=x, y=y, width=w, height=h, alpha="0")
    ET.SubElement(ambient_el, 'Image', resource=ambient)
    ET.SubElement(ambient_el, 'Variant', mode="AMBIENT", target="alpha", value="255")
    ET.SubElement(ambient_el, 'Transform', target="scaleX", value=f"[CONFIGURATION.{config}] == {index} ? 1 : 0")
    ET.SubElement(ambient_el, 'Transform', target="scaleY", value=f"[CONFIGURATION.{config}] == {index} ? 1 : 0")
    
    return [active, ambient_el]

def create_option_node(instrument):
    return ET.Element('ListOption', 
                      id=instrument.get('index'), 
                      displayName=instrument.get('name'), 
                      icon="@drawable/" + instrument.get('image'))

def main():
    if len(sys.argv) < 3:
        print("Usage: generate_watchface.py <template_path> <output_path>")
        return

    template_path = sys.argv[1]
    output_path = sys.argv[2]
    
    if not os.path.exists(template_path):
        print(f"Error: {template_path} not found.")
        sys.exit(1)

    tree = ET.parse(template_path)
    root = tree.getroot()

    # 1. Extract instruments
    instruments_root = root.find("INSTRUMENTS")
    if instruments_root is None:
        print("Error: <INSTRUMENTS> block not found in template.")
        sys.exit(1)
    
    instruments = list(instruments_root.findall("INSTRUMENT"))
    # Remove the block from the final output
    root.remove(instruments_root)

    # 2. Process placeholders
    # We'll look for custom tags: <GENERATE_OPTIONS /> and <GENERATE_HANDS />
    
    # Process ListConfigurations
    for config in root.findall(".//ListConfiguration"):
        gen_options = config.find("GENERATE_OPTIONS")
        if gen_options is not None:
            config.remove(gen_options)
            for inst in instruments:
                config.append(create_option_node(inst))

    # Process Hand Groups
    for group in root.findall(".//Group"):
        gen_hands = group.find("GENERATE_HANDS")
        if gen_hands is not None:
            hand_type = gen_hands.get("hand_type")
            group.remove(gen_hands)
            for inst in instruments:
                for node in create_hand_xml_nodes(inst, hand_type):
                    group.append(node)

    # Indent and save
    if hasattr(ET, 'indent'):
        ET.indent(tree, space="    ", level=0)
    
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    tree.write(output_path, encoding='utf-8', xml_declaration=True)
    print(f"Success! Generated {output_path} from single instrument list.")

if __name__ == "__main__":
    main()
