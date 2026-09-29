import { ErrorMessage, useField } from "formik";
import * as React from "react";
import { Theme, useTheme } from "@mui/material/styles";
import Box from "@mui/material/Box";
import OutlinedInput from "@mui/material/OutlinedInput";
import InputLabel from "@mui/material/InputLabel";
import MenuItem from "@mui/material/MenuItem";
import FormControl from "@mui/material/FormControl";
import Select from "@mui/material/Select";
import Chip from "@mui/material/Chip";


const ITEM_HEIGHT = 48;
const ITEM_PADDING_TOP = 8;
const MenuProps = {
    PaperProps: {
        style: {
            maxHeight: ITEM_HEIGHT * 4.5 + ITEM_PADDING_TOP,
            width: 250,
        },
    },
};

function getStyles(name, value, theme) {
    return {
        fontWeight:
        value.indexOf(name) === -1
                ? theme.typography.fontWeightRegular
                : theme.typography.fontWeightMedium,
    };
}

const MultipleSelect = ({ data, label, fn, handleChange, ...rest }) => {
    const [field, meta] = useField(rest);

    const theme = useTheme();
    // const [personName, setPersonName] = React.useState<string[]>([]);
    // const handleChange = (event: SelectChangeEvent<typeof personName>) => {
    //     const {
    //         target: { value },
    //     } = event;
    //     setPersonName(
    //         // On autofill we get a stringified value.
    //         typeof value === "string" ? value.split(",") : value
    //     );
    // };

            
    const result = data.length ? data.reduce((obj , cur) => ({ ...obj, [cur.id]: cur.name}), {}) : {};

    return (
        <div className="mt-2">
            <h3 className={`font-semibold border-b pb-1 mb-3 ${meta.touched && meta.error ? "text-red-500 border-red-500" : ""}`}>{label}</h3>
            <FormControl sx={{ width: 300 }}>
                <InputLabel id="demo-multiple-chip-label">{label}</InputLabel>
                <Select
                    labelId="demo-multiple-chip-label"
                    id="demo-multiple-chip"
                    multiple
                    name={field.name}
                    disabled={rest.disabled ? true : false}
                    value={field.value}
                    onChange={handleChange}
                    input={
                        <OutlinedInput id="select-multiple-chip" label={label} />
                    }
                    renderValue={(selected) => (
                        <Box
                            sx={{ display: "flex", flexWrap: "wrap", gap: 0.5 }}
                        >
                            
                            {selected.map((value) => (
                                <Chip key={value} label={result[value]} />
                            ))}
                        </Box>
                    )}
                    MenuProps={MenuProps}
                >
                    {data.map((sub) => (
                        <MenuItem
                            key={sub.id}
                            value={sub.id}
                            style={getStyles(sub.name, field.value, theme)}
                            
                        >
                            {sub.name}
                        </MenuItem>
                    ))}
                </Select>
            </FormControl>
            {meta.touched && meta.error && (
                <div className="text-sm text-red-500">
                    <ErrorMessage name={field.name} />
                </div>
            )}
        </div>
    );
};

export default MultipleSelect;